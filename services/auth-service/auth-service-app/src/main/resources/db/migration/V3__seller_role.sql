-- Phase 9: marketplace sellers. Granted by auth-service when seller-service approves a KYC application.
INSERT INTO auth.roles (id, name, description)
VALUES (4, 'ROLE_SELLER', 'Approved marketplace seller')
ON CONFLICT (id) DO NOTHING;
