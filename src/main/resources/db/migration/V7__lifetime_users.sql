UPDATE users
SET subscription_status = 'LIFETIME'
WHERE email IN (
    'erdal.yazmaci@gmail.com',
    'ozgur.altuntas@fairbit.com',
    'emin.bahadir@fairbit.com',
    'yusuf@fairbit.com',
    'bulent@fairbit.com'
);
