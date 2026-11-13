INSERT INTO faq_articles (slug, category, title, body, keywords) VALUES
('how-to-track-your-order', 'ORDERS', 'How do I track my order?',
 $b$Open **My orders** from your account menu and select the order. The tracking timeline shows each step: Placed, Confirmed, Packed, Shipped, Out for delivery and Delivered.

Once the order ships you will also see the carrier name, the tracking number and the estimated delivery date. We email you when the order ships and when it is delivered.

Orders with items from several sellers ship in several packages, each with its own tracking.$b$,
 ARRAY['track','tracking','where is my order','order status','shipment','delivery status','carrier']),
('cancel-an-order', 'ORDERS', 'Can I cancel my order?',
 $b$You can cancel an order yourself until it is shipped: while its status is **Pending payment**, **Placed**, **Confirmed** or **Packed**. Open the order and choose **Cancel order**.

Once an order is **Shipped** it can no longer be cancelled - refuse the delivery or request a return after it arrives (see the return policy).

Stock is released immediately and any coupon or coins you used are returned. Paid orders are refunded automatically.$b$,
 ARRAY['cancel','cancellation','cancel order','stop order','change my mind']),
('return-policy', 'RETURNS', 'What is the return policy?',
 $b$You can return delivered items within **7 days** of delivery if they are damaged, wrong, not as described, or no longer needed.

Open the delivered order, choose **Return items**, pick the items and a reason, and submit. Our team reviews the request; once approved, a pickup is arranged. Items must be unused and in original packaging.

Return requests after the 7 day window are not accepted.$b$,
 ARRAY['return','returns','return policy','replace','replacement','send back','7 days','exchange']),
('refund-timeline-and-method', 'RETURNS', 'When and how will I get my refund?',
 $b$Refunds go back to the **original payment method** (card), or to your **wallet** if you paid with the wallet or the order was cash on delivery.

Card refunds are issued as soon as a cancellation or an approved return is processed; banks typically take 5-7 business days to show it on your statement. Wallet refunds are instant.

You can follow the refund status on the order page.$b$,
 ARRAY['refund','refunds','money back','refund status','wallet refund','when will i get my money']),
('payment-failed-or-money-deducted', 'PAYMENTS', 'My payment failed or money was deducted',
 $b$If your payment failed, no order is confirmed. Any amount held by your bank is released automatically, usually within 5-7 business days.

If money was deducted but the order shows **Pending payment** or was cancelled, wait a few minutes and refresh - confirmations can be delayed. If the order stays cancelled, the amount is refunded to your original payment method.

Still stuck? Raise a support ticket with the order number and we will check the payment for you.$b$,
 ARRAY['payment failed','money deducted','payment declined','charged twice','card declined','transaction failed','payment issue']),
('cash-on-delivery-limits', 'PAYMENTS', 'How does Cash on Delivery work and what are the limits?',
 $b$Cash on Delivery (COD) is available for serviceable Indian pincodes. The COD order value limit is **INR 50,000** (1,000 for other currencies).

Pay the delivery partner when the order arrives. COD may be unavailable for some orders based on order value, location, or account history; you can always pay by card instead.$b$,
 ARRAY['cod','cash on delivery','pay on delivery','cash payment','cod limit']),
('using-coupons', 'PAYMENTS', 'How do I use a coupon code?',
 $b$Enter your code in the **Coupon** box at checkout and choose **Apply**. The discount shows in the order summary before you pay.

A coupon may have a minimum order value, an expiry date, a first-order-only rule or a usage limit. If a code is rejected the message tells you why. Only one coupon can be used per order. Cancelling an order returns the coupon to you.$b$,
 ARRAY['coupon','promo','promo code','discount code','voucher','offer code','coupon not working']),
('shipping-charges-and-delivery-time', 'ORDERS', 'What are the shipping charges and delivery time?',
 $b$Shipping is **free** on orders of INR 499 or more (25 for USD/EUR orders); below that it is INR 40 (4.99). Plus members get free shipping on every order.

Delivery takes about 2-5 days within India depending on your pincode. Enter your pincode on the product page to see the exact estimate and whether your area is serviceable.$b$,
 ARRAY['shipping','shipping charge','delivery charge','delivery time','how long','free shipping','pincode','estimate']),
('plus-membership', 'ACCOUNT', 'What is Plus membership?',
 $b$Plus is our paid membership. Members get **free shipping** on every order, **2x reward coins** and early access to deals.

You can join or renew from the **Membership** page in your account. When a membership expires you keep your coins but lose the member benefits until you renew.$b$,
 ARRAY['membership','plus','plus membership','subscription plan','free shipping member','member benefits']),
('reward-coins', 'ACCOUNT', 'How do reward coins work?',
 $b$You earn coins on delivered orders and through referrals. Coins can be redeemed at checkout for a discount on a later order; the value shows before you pay.

Plus members earn double coins. Coins used on an order that is cancelled are returned to you.$b$,
 ARRAY['coins','reward coins','points','rewards','referral','redeem coins']),
('login-with-otp-and-password-reset', 'ACCOUNT', 'How do I log in with an OTP or reset my password?',
 $b$On the sign-in page choose **Email me a code**, enter your email, then the 6 digit code we send. Codes expire in 5 minutes and can be used once.

To reset a password choose **Forgot password**; we email a link valid for 30 minutes. If you do not see the email, check spam.$b$,
 ARRAY['otp','login','sign in','password','forgot password','reset password','cannot login','verification code']),
('two-factor-and-account-security', 'ACCOUNT', 'How do I secure my account with two-factor authentication?',
 $b$Open **Account > Security**, choose **Enable two-factor authentication**, scan the QR code with an authenticator app and enter the 6 digit code. Save the recovery codes in a safe place - each works once.

You can also see the devices signed in to your account and sign out of any of them. We email you when a new device signs in.$b$,
 ARRAY['2fa','two factor','authenticator','security','hacked','new device','sign out devices']),
('sell-on-the-platform', 'SELLERS', 'How do I become a seller?',
 $b$Open **Sell with us**, fill in your store details and submit your KYC and bank information. Our team reviews applications and emails you when you are approved.

After approval you can list products, manage stock and orders from the seller dashboard. Bank account numbers are stored encrypted.$b$,
 ARRAY['seller','become a seller','sell','seller onboarding','sell with us','kyc','store','vendor']),
('seller-payouts', 'SELLERS', 'When do sellers get paid?',
 $b$Seller earnings are recorded per delivered shipment. After the platform commission is deducted, amounts older than 7 days become eligible for payout, and the finance team releases payouts in batches.

Returns and refunds reverse the related earnings. The seller dashboard shows your ledger and each payout.$b$,
 ARRAY['payout','seller payment','settlement','commission','ledger','seller earnings']),
('damaged-or-wrong-item', 'RETURNS', 'I received a damaged or wrong item',
 $b$We are sorry about that. Open the delivered order within 7 days, choose **Return items** and select **Damaged** or **Wrong item**. A photo in the comment helps us approve faster.

You will get a refund or replacement once the return is approved and picked up. For urgent cases, raise a support ticket from the same order.$b$,
 ARRAY['damaged','broken','wrong item','defective','not working','missing item','wrong product']),
('invoices-and-gst', 'ORDERS', 'Where can I get my invoice and GST details?',
 $b$Download the PDF invoice from the order page once the order is placed. It lists the seller, the buyer, each item, discounts, shipping and the GST breakdown (CGST + SGST within the same state, otherwise IGST).

Prices on the site are tax inclusive.$b$,
 ARRAY['invoice','gst','tax','bill','receipt','tax invoice']),
('gift-cards-and-wallet', 'PAYMENTS', 'How do gift cards and the wallet work?',
 $b$Redeem a gift card code under **Wallet**; the value is added to your wallet balance and can be used at checkout. Gift cards are valid for one year.

Refunds that go to the wallet are available instantly. Wallet balance cannot be withdrawn to a bank account.$b$,
 ARRAY['gift card','wallet','wallet balance','redeem gift card','store credit']),
('price-drop-and-stock-alerts', 'ORDERS', 'How do I get price drop or back-in-stock alerts?',
 $b$On a product page choose **Alert me on price drop** (optionally with a target price) or **Notify me** when an item is out of stock. We email you once when the condition is met and you can re-arm the alert any time.

Manage all alerts from **Account > Alerts**.$b$,
 ARRAY['price drop','alert','notify me','back in stock','out of stock','notification']),
('subscriptions-subscribe-and-save', 'ORDERS', 'How do subscriptions (subscribe and save) work?',
 $b$Choose **Subscribe** on eligible products and pick how often you want it delivered. We place the order automatically on schedule and email you each time.

You can skip, pause or cancel from **Account > Subscriptions**. After repeated payment failures a subscription pauses by itself.$b$,
 ARRAY['subscription','subscribe','subscribe and save','recurring order','auto order','repeat delivery']),
('delete-account-and-export-data', 'ACCOUNT', 'How do I export or delete my data?',
 $b$From **Account > Privacy** you can request a download of your data and ask for your account to be deleted. Deletion has a 7 day grace period during which you can cancel it.

Accounts with open orders or pending refunds cannot be deleted until those finish. Order records needed for tax and accounting are kept without your personal details.$b$,
 ARRAY['delete account','export data','privacy','gdpr','close account','personal data','download my data']),
('contact-support', 'OTHER', 'How do I contact customer support?',
 $b$Use the chat assistant at the bottom of the page for quick answers. To talk to a person, raise a ticket from **Account > Support** (you can attach an order) and our team replies by email and in the ticket thread.

Payment issues are prioritised. You can rate the support you received once a ticket is resolved.$b$,
 ARRAY['contact','support','help','talk to human','customer care','complaint','agent','phone number'])
ON CONFLICT (slug) DO NOTHING;
