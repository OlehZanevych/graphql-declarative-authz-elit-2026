-- The five ITC 2019 tables of the Section 7.7 experiment (identifiers only), loaded after the
-- application's schema.sql, which supplies users, groups and permissions.
CREATE TABLE itc_courses (id bigint PRIMARY KEY);
CREATE TABLE itc_configs (id bigint PRIMARY KEY, course_id bigint NOT NULL REFERENCES itc_courses);
CREATE TABLE itc_subparts (id bigint PRIMARY KEY, config_id bigint NOT NULL REFERENCES itc_configs);
CREATE TABLE itc_classes (id bigint PRIMARY KEY, subpart_id bigint NOT NULL REFERENCES itc_subparts, parent_class_id bigint);
CREATE TABLE itc_rooms (id bigint PRIMARY KEY);
CREATE TABLE itc_class_rooms (class_id bigint NOT NULL REFERENCES itc_classes, room_id bigint NOT NULL REFERENCES itc_rooms,
                              PRIMARY KEY (class_id, room_id));
