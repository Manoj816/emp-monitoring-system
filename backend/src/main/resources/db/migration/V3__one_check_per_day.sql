-- Default policy: a single randomized check per day during working hours.
-- (Demo overrides live in the agent so a check can be forced at an exact time.)
UPDATE check_policies SET max_checks_per_day = 1, updated_at = now() WHERE name = 'default';
