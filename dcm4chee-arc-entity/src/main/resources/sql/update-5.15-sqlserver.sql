alter table patient add verification_status int default 0 not null;
alter table patient add failed_verifications int default 0 not null;
alter table patient add verification_time datetime2;

alter table stgcmt_result add batch_id varchar(255);

create index UK_e7rsyrt9n2mccyv1fcd2s6ikv on patient (verification_status);
create index UK_bay8wkvwegw3pmyeypv2v93k1 on patient (verification_time);

create index UK_f718gnu5js0mdg39q6j7fklia on stgcmt_result (batch_id);