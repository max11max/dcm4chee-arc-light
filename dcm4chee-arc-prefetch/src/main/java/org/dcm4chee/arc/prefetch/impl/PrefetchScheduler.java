/*
 * **** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015-2018
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * **** END LICENSE BLOCK *****
 *
 */

package org.dcm4chee.arc.prefetch.impl;

import org.dcm4che3.data.*;
import org.dcm4che3.net.*;
import org.dcm4che3.net.hl7.HL7Application;
import org.dcm4che3.net.hl7.HL7DeviceExtension;
import org.dcm4che3.net.hl7.UnparsedHL7Message;
import org.dcm4che3.util.ReverseDNS;
import org.dcm4chee.arc.HL7ConnectionEvent;
import org.dcm4chee.arc.conf.*;
import org.dcm4chee.arc.qmgt.QueueSizeLimitExceededException;
import org.dcm4chee.arc.query.scu.CFindSCU;
import org.dcm4chee.arc.retrieve.ExternalRetrieveContext;
import org.dcm4chee.arc.retrieve.mgt.RetrieveManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.io.IOException;
import java.net.Socket;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Nov 2018
 */
@ApplicationScoped
public class PrefetchScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PrefetchScheduler.class);

    @Inject
    private Device device;

    @Inject
    private CFindSCU findSCU;

    @Inject
    private RetrieveManager retrieveManager;

    public void onHL7Connection(@Observes HL7ConnectionEvent event) {
        if (!(event.getType() == HL7ConnectionEvent.Type.MESSAGE_PROCESSED && event.getException() == null))
            return;

        UnparsedHL7Message hl7Message = event.getHL7Message();
        HL7Application hl7App = device.getDeviceExtension(HL7DeviceExtension.class)
                .getHL7Application(hl7Message.msh().getReceivingApplicationWithFacility(), true);
        if (hl7App == null)
            return;

        ArchiveHL7ApplicationExtension arcHL7App =
                hl7App.getHL7ApplicationExtension(ArchiveHL7ApplicationExtension.class);
        if (arcHL7App == null || !arcHL7App.hasHL7PrefetchRules())
            return;

        Socket sock = event.getSocket();
        String host = ReverseDNS.hostNameOf(sock.getInetAddress());
        HL7Fields hl7Fields = new HL7Fields(hl7Message, hl7App.getHL7DefaultCharacterSet());
        Calendar now = Calendar.getInstance();
        ArchiveDeviceExtension arcdev = device.getDeviceExtensionNotNull(ArchiveDeviceExtension.class);
        arcHL7App.hl7PrefetchRules()
                .filter(rule -> rule.match(host, hl7Fields))
                .forEach(rule -> prefetch(sock, hl7Fields, rule, arcdev, now));
    }

    private void prefetch(Socket sock, HL7Fields hl7Fields, HL7PrefetchRule rule, ArchiveDeviceExtension arcdev,
                          Calendar now) {
        try {
            LOG.info("{}: Apply {}", sock, rule);
            Date notRetrievedAfter = new Date(
                    now.getTimeInMillis() - rule.getSuppressDuplicateRetrieveInterval().getSeconds() * 1000L);
            String cx = hl7Fields.get("PID-3", null);
            IDWithIssuer pid = new IDWithIssuer(cx);
            String batchID = rule.getCommonName() + '[' + cx + ']';
            if (rule.getEntitySelectors().length == 0) {
                prefetch(pid, batchID, new Attributes(0), rule, arcdev, notRetrievedAfter);
            } else {
                for (EntitySelector selector : rule.getEntitySelectors()) {
                    prefetch(pid, batchID, selector.getQueryKeys(hl7Fields), rule, arcdev, notRetrievedAfter);
                }
            }
        } catch (Exception e) {
            LOG.warn("{}: Failed to apply {}:\n", sock, rule, e);
        }
    }

    private void prefetch(IDWithIssuer pid, String batchID, Attributes queryKeys,
                          HL7PrefetchRule rule, ArchiveDeviceExtension arcdev, Date notRetrievedAfter)
            throws Exception {
        Attributes keys = new Attributes(queryKeys.size() + 4);
        keys.addAll(queryKeys);
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        if (keys.containsValue(Tag.StudyInstanceUID)) {
            scheduleRetrieveTask(keys, rule, batchID, notRetrievedAfter);
            return;
        }
        keys.setString(Tag.PatientID, VR.LO, pid.getID());
        Issuer issuer = pid.getIssuer();
        if (issuer != null)
            issuer.toIssuerOfPatientID(keys);
        keys.setNull(Tag.StudyInstanceUID, VR.UI);
        ApplicationEntity localAE = arcdev.getDevice().getApplicationEntity(rule.getAETitle(), true);
        EnumSet<QueryOption> queryOptions = EnumSet.of(QueryOption.DATETIME);
        Association as = findSCU.openAssociation(
                localAE, rule.getPrefetchCFindSCP(), UID.StudyRootQueryRetrieveInformationModelFIND, queryOptions);
        try {
            DimseRSP dimseRSP = findSCU.query(as, Priority.NORMAL, keys, 0, 1, null);
            dimseRSP.next();
            do {
                if (Status.isPending(dimseRSP.getCommand().getInt(Tag.Status, -1))) {
                    scheduleRetrieveTask(dimseRSP.getDataset(), rule, batchID, notRetrievedAfter);
                }
            } while (dimseRSP.next()) ;
        } finally {
            if (as != null)
                try {
                    as.release();
                } catch (IOException e) {
                    LOG.info("{}: Failed to release association:\\n", as, e);
                }
        }
    }

    private void scheduleRetrieveTask(Attributes keys, HL7PrefetchRule rule, String batchID, Date notRetrievedAfter)
            throws QueueSizeLimitExceededException {
        ExternalRetrieveContext ctx = new ExternalRetrieveContext()
                .setLocalAET(rule.getAETitle())
                .setRemoteAET(rule.getPrefetchCMoveSCP())
                .setDestinationAET(rule.getPrefetchCStoreSCP())
                .setKeys(new Attributes(keys, Tag.QueryRetrieveLevel, Tag.StudyInstanceUID));
        retrieveManager.scheduleRetrieveTask(Priority.NORMAL, ctx, batchID, notRetrievedAfter);
    }
}
