-- Constructed authorization workload (not a deployed policy): a benchmark caller in one group,
-- EDIT on every tenth course through the group, and MANAGE on one room directly.
ALTER TABLE itc_classes ADD FOREIGN KEY (parent_class_id) REFERENCES itc_classes;
INSERT INTO users (id, email, first_name, last_name, password_hash, must_change_password)
VALUES (1001, 'bench-caller@example.invalid', 'Bench', 'Caller', 'x', false);
INSERT INTO groups (id, name) VALUES (1, 'coordinators');
INSERT INTO user_groups (user_id, group_id) VALUES (1001, 1);
INSERT INTO permissions (grantee_type, group_id, resource_type, resource_id, level)
SELECT 'GROUP', 1, 'ITC_COURSE', id, 'EDIT' FROM itc_courses WHERE id % 10 = 0;
INSERT INTO permissions (grantee_type, user_id, resource_type, resource_id, level)
SELECT 'USER', 1001, 'ITC_ROOM', min(id), 'MANAGE' FROM itc_rooms;
ANALYZE;
