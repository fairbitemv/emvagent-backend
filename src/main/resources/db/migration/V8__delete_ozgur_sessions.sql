DELETE FROM chat_messages
WHERE session_id IN (
    SELECT cs.id FROM chat_sessions cs
    JOIN users u ON u.username = cs.username
    WHERE u.email = 'ozgur.altuntas@fairbit.com'
);

DELETE FROM chat_sessions
WHERE username IN (
    SELECT username FROM users WHERE email = 'ozgur.altuntas@fairbit.com'
);
