"""Vocabulary pools (Faker + curated taxonomy + optional LLM text).

Pools are built once (deterministically from the seed) and cached; all of them are numpy
bytes arrays so generators can fancy-index them.  Text never contains tab / newline / backslash,
so it can be streamed into COPY text format without escaping.
"""
from __future__ import annotations

import json
import pickle
import re
import unicodedata
from pathlib import Path

import numpy as np

# root, [(sub, nouns, median USD price)]
TAXONOMY = [
    ("Electronics", [("Mobiles & Smartphones", ["Smartphone", "Phone", "5G Phone"], 320), ("Headphones & Earbuds", ["Headphones", "Earbuds", "Headset"], 70),
                     ("Televisions", ["Smart TV", "LED TV", "4K TV"], 480), ("Cameras", ["Mirrorless Camera", "Action Camera", "Camera"], 520),
                     ("Smart Watches", ["Smartwatch", "Fitness Band", "Watch"], 120), ("Speakers", ["Bluetooth Speaker", "Soundbar", "Speaker"], 60)]),
    ("Computers & Accessories", [("Laptops", ["Laptop", "Notebook", "Ultrabook"], 780), ("Monitors", ["Monitor", "Gaming Monitor", "4K Monitor"], 240),
                                 ("Keyboards & Mice", ["Mechanical Keyboard", "Wireless Mouse", "Keyboard"], 45), ("Storage", ["SSD", "Portable Drive", "Flash Drive"], 65),
                                 ("Networking", ["WiFi Router", "Mesh System", "Switch"], 85)]),
    ("Home & Kitchen", [("Cookware", ["Frying Pan", "Pressure Cooker", "Cookware Set"], 40), ("Small Appliances", ["Air Fryer", "Blender", "Toaster"], 70),
                        ("Furniture", ["Office Chair", "Bookshelf", "Study Table"], 160), ("Home Decor", ["Wall Clock", "Table Lamp", "Cushion Cover"], 25),
                        ("Cleaning", ["Vacuum Cleaner", "Robot Vacuum", "Mop"], 120)]),
    ("Fashion", [("Men's Clothing", ["T-Shirt", "Jacket", "Jeans"], 30), ("Women's Clothing", ["Kurta", "Dress", "Top"], 32),
                 ("Footwear", ["Running Shoes", "Sneakers", "Sandals"], 55), ("Bags & Luggage", ["Backpack", "Trolley Bag", "Handbag"], 48),
                 ("Watches & Jewellery", ["Analog Watch", "Bracelet", "Necklace"], 60)]),
    ("Beauty & Personal Care", [("Skin Care", ["Face Serum", "Moisturiser", "Sunscreen"], 18), ("Hair Care", ["Shampoo", "Hair Dryer", "Hair Oil"], 14),
                                ("Grooming", ["Trimmer", "Electric Shaver", "Razor"], 28), ("Fragrances", ["Perfume", "Deodorant", "Eau de Toilette"], 35)]),
    ("Sports & Outdoors", [("Fitness", ["Yoga Mat", "Dumbbell Set", "Resistance Bands"], 28), ("Cycling", ["Mountain Bike", "Helmet", "Bike Lock"], 90),
                           ("Camping", ["Tent", "Sleeping Bag", "Trekking Backpack"], 85), ("Team Sports", ["Football", "Cricket Bat", "Badminton Racket"], 30)]),
    ("Books & Stationery", [("Books", ["Paperback", "Hardcover", "Box Set"], 14), ("Stationery", ["Notebook Pack", "Fountain Pen", "Planner"], 9),
                            ("Art Supplies", ["Colour Pencil Set", "Acrylic Paint Kit", "Sketch Pad"], 16)]),
    ("Toys & Games", [("Building Toys", ["Building Blocks", "Construction Set", "Brick Set"], 30), ("Board Games", ["Board Game", "Strategy Game", "Puzzle"], 24),
                      ("Remote Control", ["RC Car", "Drone", "RC Helicopter"], 55)]),
    ("Health & Wellness", [("Supplements", ["Multivitamin", "Protein Powder", "Omega-3"], 26), ("Medical Devices", ["BP Monitor", "Thermometer", "Glucometer"], 38),
                           ("Personal Wellness", ["Massager", "Heating Pad", "Air Purifier"], 65)]),
    ("Grocery & Gourmet", [("Beverages", ["Coffee Beans", "Green Tea", "Cold Brew"], 12), ("Snacks", ["Protein Bars", "Mixed Nuts", "Granola"], 9),
                           ("Pantry", ["Olive Oil", "Basmati Rice", "Spice Box"], 11)]),
]
ROOTS = [t[0] for t in TAXONOMY]
SUBS = [(ri, s[0], s[1], s[2]) for ri, t in enumerate(TAXONOMY) for s in t[1]]
N_ROOT, N_SUB = len(TAXONOMY), len(SUBS)

COUNTRIES = ["IN", "US", "GB", "DE", "FR", "AE", "SG", "AU"]
COUNTRY_W = np.array([0.62, 0.17, 0.05, 0.05, 0.03, 0.03, 0.02, 0.03])
CURRENCY = {"IN": "INR", "US": "USD", "GB": "USD", "DE": "EUR", "FR": "EUR", "AE": "USD", "SG": "USD", "AU": "USD"}
# city, state, pincode-prefix (3 digits for IN, 2 otherwise), country
CITIES = [
    ("Bengaluru", "Karnataka", "560", "IN"), ("Mysuru", "Karnataka", "570", "IN"), ("Mumbai", "Maharashtra", "400", "IN"),
    ("Pune", "Maharashtra", "411", "IN"), ("New Delhi", "Delhi", "110", "IN"), ("Chennai", "Tamil Nadu", "600", "IN"),
    ("Coimbatore", "Tamil Nadu", "641", "IN"), ("Hyderabad", "Telangana", "500", "IN"), ("Kolkata", "West Bengal", "700", "IN"),
    ("Ahmedabad", "Gujarat", "380", "IN"), ("Surat", "Gujarat", "395", "IN"), ("Jaipur", "Rajasthan", "302", "IN"),
    ("Lucknow", "Uttar Pradesh", "226", "IN"), ("Noida", "Uttar Pradesh", "201", "IN"), ("Kochi", "Kerala", "682", "IN"),
    ("Chandigarh", "Chandigarh", "160", "IN"), ("Bhopal", "Madhya Pradesh", "462", "IN"), ("Patna", "Bihar", "800", "IN"),
    ("New York", "NY", "10", "US"), ("San Francisco", "CA", "94", "US"), ("Austin", "TX", "78", "US"), ("Seattle", "WA", "98", "US"),
    ("Chicago", "IL", "60", "US"), ("Boston", "MA", "02", "US"), ("London", "England", "EC", "GB"), ("Manchester", "England", "M1", "GB"),
    ("Berlin", "Berlin", "10", "DE"), ("Munich", "Bavaria", "80", "DE"), ("Hamburg", "Hamburg", "20", "DE"),
    ("Paris", "Ile-de-France", "75", "FR"), ("Lyon", "Auvergne-Rhone-Alpes", "69", "FR"),
    ("Dubai", "Dubai", "00", "AE"), ("Singapore", "Singapore", "01", "SG"), ("Sydney", "NSW", "20", "AU"), ("Melbourne", "VIC", "30", "AU"),
]
CARRIERS_IN = ["Delhivery", "Blue Dart", "Ecom Express", "XpressBees", "Shadowfax", "India Post"]
CARRIERS_INTL = ["DHL Express", "FedEx", "UPS", "Royal Mail", "Australia Post"]
BANKS = ["HDFC Bank", "ICICI Bank", "Axis Bank", "SBI Card", "Kotak Mahindra", "Citi", "Amex", "Chase"]
COLORS = ["Black", "White", "Silver", "Space Grey", "Navy Blue", "Red", "Green", "Rose Gold", "Midnight", "Olive", "Grey", "Blue"]
MATERIALS = ["Aluminium", "Stainless Steel", "ABS Plastic", "Cotton", "Polyester", "Leather", "Silicone", "Bamboo", "Glass", "Ceramic", "Nylon", "Wood"]
MODIFIERS = ["Pro", "Plus", "Lite", "Max", "Ultra", "Classic", "Essential", "Elite", "Prime", "Series 2", "Gen 3", "Edition"]
SIZES = ["XS", "S", "M", "L", "XL", "XXL"]
STORAGES = ["64GB", "128GB", "256GB", "512GB", "1TB"]

_SENT = {
    "intro": ["{b} {n} is engineered for people who care about everyday reliability.", "Meet the {b} {n}: a thoughtful balance of performance, design and value.",
              "The {b} {n} brings premium features to a price that makes sense.", "Built for daily use, the {b} {n} keeps up whether you are at home, at work or on the move."],
    "body": ["Crafted from {m}, it feels solid in the hand and is designed to last for years of regular use.",
             "Every unit goes through multi-stage quality checks before it leaves the warehouse.",
             "Setup takes minutes and the included guide walks you through each step.",
             "A refined finish in {c} blends into any space or setup.",
             "Energy-efficient design helps reduce running costs without compromising output.",
             "Thoughtful details such as rounded edges and a balanced weight make it comfortable to use for long sessions.",
             "Compatible with the most common accessories, so it fits neatly into the setup you already own.",
             "Backed by a one year manufacturer warranty and responsive customer support.",
             "Lightweight yet sturdy, it is easy to carry and simple to store when not in use.",
             "Designed with feedback from thousands of customers, with improvements in durability and ease of use.",
             "Tested for consistent performance across a wide range of conditions.",
             "Packaged in recyclable materials to keep the footprint small.",
             "Great as a gift for family, friends or colleagues."],
    "close": ["Order today and enjoy fast, tracked delivery with easy returns.", "Add it to your cart now and experience the difference.",
              "Pair it with our recommended accessories for the complete experience."],
}
_REV = {
    "pos": ["Absolutely love it, works exactly as described.", "Great value for the money and arrived earlier than expected.", "Build quality is excellent and it feels premium.",
            "Easy to set up and has been flawless so far.", "Better than I expected, would happily buy again.", "Does the job perfectly, very happy with this purchase.",
            "Packaging was secure and the product looks even better in person.", "Using it daily for a month now and it still performs like new.",
            "Highly recommended, my whole family is impressed.", "Fantastic product and quick delivery, five stars."],
    "neu": ["It is decent for the price, nothing extraordinary but gets the job done.", "Works fine, though the instructions could have been clearer.",
            "Average quality, I expected slightly more but it is acceptable.", "Good overall, a couple of minor issues with the finish.",
            "Does what it says. Delivery took a little longer than promised."],
    "neg": ["Stopped working after a couple of weeks, very disappointed.", "Not as described, the quality feels cheap.", "Packaging was damaged and the product has scratches.",
            "Poor value for money, I would not recommend it.", "Support was slow to respond and the issue is still unresolved.",
            "Arrived with a defect, had to raise a return request."],
}
_TITLE = {"pos": ["Excellent!", "Worth every penny", "Highly recommended", "Great purchase", "Love it", "Five stars"],
          "neu": ["Decent", "It is okay", "Average but fine", "Good, not great"],
          "neg": ["Disappointed", "Not worth it", "Poor quality", "Do not buy", "Stopped working"]}
_TICKET = {
    "ORDERS": [("Where is my order?", "My order has not arrived yet and the tracking page has not updated. Could you please check the status and let me know the expected delivery date?"),
               ("Need to change delivery address", "I entered the wrong address while placing the order. Can the delivery address be updated before it ships?"),
               ("Item missing from order", "I received my package but one of the items listed in the invoice was not in the box. Please advise how to proceed.")],
    "PAYMENTS": [("Charged twice for one order", "My card statement shows two charges for the same order. Please verify and refund the duplicate payment."),
                 ("Refund not received", "My return was approved several days ago but the refund has not reached my account yet."),
                 ("Coupon not applied", "The coupon code was accepted at checkout but the discount was not reflected in the final amount.")],
    "RETURNS": [("How do I return an item?", "The product I received does not match the description. I would like to start a return, please share the next steps."),
                ("Return pickup not scheduled", "My return was approved but no pickup has been scheduled. Could you arrange a pickup slot?")],
    "ACCOUNT": [("Unable to log in", "I keep getting an invalid credentials message even after resetting my password. Please help me regain access."),
                ("Update my phone number", "I would like to change the phone number linked to my account but the OTP is not arriving.")],
    "SELLERS": [("Seller not responding", "I contacted the seller about my order but have not received any reply for several days."),
                ("Counterfeit product suspected", "The item looks different from the brand's genuine product. I would like to report this listing.")],
    "OTHER": [("Question about warranty", "Could you confirm the warranty terms for the product I bought and how to claim it?"),
              ("Feedback on the app", "The checkout page was slow on my phone. Sharing this in case it helps improve the experience.")],
}
_AGENT = ["Thanks for reaching out. I have looked into this and I am escalating it to the relevant team.", "I am sorry for the inconvenience. I have raised this with the carrier and will update you within 24 hours.",
          "Your refund has been initiated and should reflect in your account in 3 to 5 business days.", "Could you please share a photo of the item and the packaging so we can proceed?",
          "I have updated your request and you will receive a confirmation email shortly.", "Thank you for your patience. This has been resolved from our side, please let us know if anything else is needed."]
_CUST = ["Thanks for the quick reply.", "I have attached the details you asked for.", "Any update on this please?", "That works for me, thank you.", "I am still facing the same problem."]
_CHAT_Q = ["where is my order", "how do i return an item", "track my refund", "change my address", "apply a coupon", "talk to an agent", "is cash on delivery available", "reset my password"]
_CHAT_A = ["You can track your order from the Orders page. Would you like me to open it?", "Returns can be requested within 10 days of delivery from the order details page.",
           "Refunds to the original payment method take 3 to 5 business days.", "I can connect you with a support agent if you like.", "Cash on delivery is available for eligible pincodes."]
_NOTIF = {"ORDER": ["Your order has been placed", "Your order is confirmed", "Your order has shipped", "Out for delivery today", "Your order was delivered", "Your order was cancelled", "Refund processed"],
          "ALERT": ["Price drop on an item you saved", "Back in stock", "Your cart is waiting"],
          "PROMO": ["Festive sale starts now", "Flat discount on your next order", "Deals picked for you", "Plus members get free shipping"],
          "ACCOUNT": ["New sign-in to your account", "Your password was changed", "Verify your email address"],
          "SYSTEM": ["Scheduled maintenance tonight", "We have updated our policies"]}
_AUDIT_ACTIONS = ["ORDER_STATUS_CHANGED", "RETURN_DECISION", "PRODUCT_UPDATED", "PRICE_CHANGED", "COUPON_CREATED", "SELLER_APPROVED", "REFUND_ISSUED", "USER_LOCKED", "STOCK_ADJUSTED", "TICKET_ASSIGNED"]
_FAQ = {"ORDERS": ["How do I track my order?", "Can I change or cancel an order?", "What are the delivery timelines?"],
        "PAYMENTS": ["Which payment methods are accepted?", "How do refunds work?", "Is cash on delivery available?"],
        "RETURNS": ["What is the return policy?", "How do I exchange an item?"],
        "ACCOUNT": ["How do I reset my password?", "How do I enable two-factor authentication?"],
        "SELLERS": ["How do I become a seller?", "When do sellers get paid?"], "OTHER": ["How do I contact support?", "How do coins and wallet work?"]}


def _ascii(s: str) -> str:
    return unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode()


def _clean(s: str) -> str:
    return re.sub(r"[\t\r\n\\]+", " ", s).strip()


def _b(lst) -> np.ndarray:
    return np.array([_clean(x).encode("utf-8") for x in lst], dtype="S")


class Vocab:
    """Plain attribute bag of bytes-array pools."""

    def __init__(self):
        self.p: dict[str, np.ndarray] = {}

    def __getitem__(self, k):
        return self.p[k]

    def get(self, k, default=None):
        return self.p.get(k, default)


def build_vocab(seed: int, llm_pools: dict | None = None) -> Vocab:
    from faker import Faker
    v = Vocab()
    loc = {"IN": "en_IN", "US": "en_US", "GB": "en_GB", "DE": "de_DE", "FR": "fr_FR", "AE": "en_US", "SG": "en_US", "AU": "en_AU"}
    fakers = {}
    for c, lc in loc.items():
        f = Faker(lc)
        f.seed_instance(seed + sum(map(ord, c)))
        fakers[c] = f
    for c in COUNTRIES:
        f = fakers[c]
        v.p[f"first_{c}"] = _b([_ascii(f.first_name()) or "Alex" for _ in range(1500)])
        v.p[f"last_{c}"] = _b([_ascii(f.last_name()) or "Smith" for _ in range(1500)])
    f = fakers["US"]
    v.p["streets"] = _b([_ascii(f.street_name()) for _ in range(1500)])
    v.p["domains"] = _b(["gmail.com", "yahoo.com", "outlook.com", "hotmail.com", "proton.me", "icloud.com", "example.com", "mail.com"])
    syl = ["ze", "nu", "vo", "ka", "ri", "lo", "ta", "mi", "su", "ex", "or", "ba", "li", "ven", "tra", "dex", "no", "pi", "qua", "lum", "ar", "ion", "fy", "co"]
    rng = np.random.default_rng(seed)
    brands = []
    for _ in range(600):
        k = rng.integers(2, 4)
        brands.append("".join(rng.choice(syl, k)).capitalize())
    v.p["brands"] = _b(sorted(set(brands)))
    v.p["companies"] = _b([_ascii(Faker("en_IN").company()) if False else _ascii(f.company()) for _ in range(800)])
    v.p["colors"], v.p["materials"], v.p["modifiers"] = _b(COLORS), _b(MATERIALS), _b(MODIFIERS)
    for k in ("pos", "neu", "neg"):
        v.p[f"rev_{k}"] = _b(_REV[k])
        v.p[f"title_{k}"] = _b(_TITLE[k])
    for k, lst in _SENT.items():
        v.p[f"s_{k}"] = _b(lst)
    v.p["agent_msgs"], v.p["cust_msgs"] = _b(_AGENT), _b(_CUST)
    v.p["chat_q"], v.p["chat_a"] = _b(_CHAT_Q), _b(_CHAT_A)
    v.p["audit_actions"] = _b(_AUDIT_ACTIONS)
    v.p["carriers_in"], v.p["carriers_intl"], v.p["banks"] = _b(CARRIERS_IN), _b(CARRIERS_INTL), _b(BANKS)
    for cat, lst in _TICKET.items():
        v.p[f"tsub_{cat}"] = _b([a for a, _ in lst])
        v.p[f"tbody_{cat}"] = _b([b for _, b in lst])
    for t, lst in _NOTIF.items():
        v.p[f"notif_{t}"] = _b(lst)
    v.p["lorem"] = _b([_ascii(f.sentence(nb_words=14)) for _ in range(300)])
    v.p["bio"] = _b([_ascii(f.paragraph(nb_sentences=3)) for _ in range(300)])
    # optional LLM pools: replace/extend the template pools with model-written text
    if llm_pools:
        for k, lst in llm_pools.items():
            lst = [x for x in lst if isinstance(x, str) and x.strip()]
            if lst:
                v.p[f"llm_{k}"] = _b(lst)
    return v


def load_vocab(cache_dir: Path, seed: int, llm_file: Path | None = None) -> Vocab:
    cache_dir.mkdir(parents=True, exist_ok=True)
    pools = json.loads(llm_file.read_text(encoding="utf-8")) if llm_file and llm_file.exists() else None
    tag = f"{seed}-{llm_file.stat().st_mtime_ns if pools else 0}"
    f = cache_dir / f"vocab-{tag}.pkl"
    if f.exists():
        try:
            return pickle.loads(f.read_bytes())
        except Exception:
            pass
    v = build_vocab(seed, pools)
    f.write_bytes(pickle.dumps(v))
    return v
