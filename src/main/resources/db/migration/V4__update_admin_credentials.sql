-- Update admin user: username → admin, email → admin@fairbit.com, password → fairbit2026 (BCrypt)
UPDATE users
SET
    username = 'admin',
    email    = 'admin@fairbit.com',
    password = '$2a$12$qBLFS/4v9gGa9LBfdDoQ0OHeqEhoC6qfscIvAHzth05Tz8PLeOFV6'
WHERE username = 'admin@emvagent.com';
