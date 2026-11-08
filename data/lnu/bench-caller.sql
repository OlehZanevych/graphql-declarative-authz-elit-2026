-- The benchmark caller of the latency experiment (Section 7.6): a test account in group 1, which holds
-- MANAGE on faculty 1 in the snapshot. Statement counts do not depend on the caller.
INSERT INTO users (id, email, first_name, last_name, password_hash, must_change_password)
VALUES (1001, 'bench-caller@example.invalid', 'Bench', 'Caller', 'x', false);
INSERT INTO user_groups (user_id, group_id) VALUES (1001, 1);
