// Generates infra/scripts/update-product-images.sql: relevant image URLs per product
// name (Wikimedia Commons search). Usage: node gen-product-images.mjs
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const names = execFileSync('docker', ['exec', 'ecommerce-postgres', 'psql', '-U', 'product_service', '-d', 'product_db', '-At',
  '-c', 'select distinct name from product.products order by 1']).toString().trim().split('\n').map(s => s.trim()).filter(Boolean);

// Search-term overrides where the product name alone is ambiguous.
const Q = {
  'Dune': 'Dune Frank Herbert book cover', 'Educated': 'Tara Westover', 'Atomic Habits': 'Atomic Habits book',
  'The Midnight Library': 'library interior books', 'The Silent Patient': 'The Silent Patient book',
  'Bag Backpack': 'backpack', 'Belt Leather': 'leather belt', 'Hat Baseball': 'baseball cap', 'Watch Analog': 'analog wristwatch',
  'Socks Pack 6': 'socks', 'Gloves Winter': 'winter gloves', 'Scarf Winter': 'scarf', 'Wallet Slim': 'leather wallet',
  'Dress Casual': 'dress clothing', 'Hoodie Fleece': 'hoodie', 'Jacket Bomber': 'bomber jacket', 'Jeans Slim Fit': 'blue jeans',
  'Pants Work': 'work trousers', 'Shorts Swim': 'swim shorts', 'T-Shirt Cotton': 't-shirt',
  'Boots Leather': 'leather boots', 'Sandals Comfort': 'sandals', 'Shoes Formal': 'dress shoes', 'Sneakers Running': 'running shoes',
  'Audio Interface': 'audio interface recording', 'Boom Arm Stand': 'microphone boom arm', 'Earbuds Pro': 'wireless earbuds',
  'Headphones Gaming': 'gaming headset', 'Headphones Wireless': 'wireless headphones', 'MIDI Keyboard': 'MIDI keyboard controller',
  'Microphone USB': 'USB microphone', 'Pop Filter': 'microphone pop filter', 'Speaker Bluetooth': 'bluetooth speaker',
  'Studio Monitors Pair': 'studio monitor speakers', 'XLR Cables': 'XLR cable',
  'Desktop PC Gaming': 'gaming desktop computer', 'Laptop Pro 13': 'laptop computer', 'Laptop Pro 15': 'laptop',
  'Laptop Ultrabook': 'ultrabook laptop', 'Monitor 1080p 27"': 'computer monitor', 'Monitor 4K 32"': '4K computer monitor',
  '27-inch 4K Monitor': '4K computer monitor', 'Noise-Cancelling Headset': 'noise cancelling headphones',
  'USB-C Docking Station': 'laptop docking station', 'Docking Station': 'laptop docking station',
  'Cable Management Kit': 'cable management', 'Cooling Pad Laptop': 'laptop cooling pad', 'Desk Organizer': 'desk organizer',
  'External HDD 2TB': 'hard disk drive', 'External SSD 1TB': 'external solid state drive', 'HDMI Cable 2.1': 'HDMI cable',
  'Laptop Stand': 'laptop stand', 'Mechanical Keyboard': 'mechanical keyboard', 'Monitor Light Bar': 'monitor light bar',
  'Mouse Pad Gaming': 'gaming mouse pad', 'Phone Charger Fast': 'phone charger', 'Power Bank 20000mAh': 'power bank',
  'RGB Lighting Kit': 'RGB LED strip', 'USB Flash Drive 64GB': 'USB flash drive', 'USB-C Hub': 'USB hub', 'Webcam 1080p': 'webcam',
  'Wireless Mouse': 'wireless computer mouse',
  'Dumbbells Set': 'dumbbells', 'Jump Rope': 'jump rope', 'Kettlebell': 'kettlebell', 'Medicine Ball': 'medicine ball',
  'Resistance Bands': 'resistance band exercise', 'Stationary Bike': 'stationary exercise bike', 'Treadmill Folding': 'treadmill', 'Yoga Mat': 'yoga mat',
  'Area Rug 8x10': 'area rug', 'Bed Frame Queen': 'bed frame', 'Bookshelf 5-Tier': 'bookshelf', 'Coffee Table': 'coffee table',
  'Desk Home Office': 'office desk', 'Dining Chairs Set 4': 'dining chairs', 'Dining Table': 'dining table', 'Mattress Queen': 'mattress',
  'Office Chair Ergonomic': 'office chair', 'Sofa 3-Seater': 'sofa couch',
  'Air Fryer': 'air fryer', 'Blender High-Speed': 'kitchen blender', 'Coffee Maker': 'coffee maker', 'Cutting Board Set': 'cutting board',
  'Instant Pot': 'electric pressure cooker', 'Knife Set 7-Piece': 'kitchen knife set', 'Microwave 1000W': 'microwave oven',
  'Pots & Pans Set': 'cookware pots pans', 'Toaster 4-Slice': 'toaster',
  'Ceiling Light 60W': 'ceiling light fixture', 'Curtain Rod Set': 'curtain rod', 'Desk Lamp LED': 'desk lamp', 'Floor Lamp Arc': 'floor lamp',
  'Table Lamp': 'table lamp', 'Wall Lamp LED': 'wall lamp',
  'Backpack 65L': 'hiking backpack', 'Bicycle Mountain': 'mountain bike', 'Football Soccer': 'soccer ball', 'Hiking Boots': 'hiking boots',
  'Skateboard Pro': 'skateboard', 'Sleeping Bag': 'sleeping bag', 'Tent Camping 4-Person': 'camping tent',
  'Sunglasses': 'sunglasses',
};

const sleep = ms => new Promise(r => setTimeout(r, ms));
async function search(q) {
  const url = `https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=20&prop=imageinfo&iiprop=url|mime|size&iiurlwidth=800&gsrsearch=${encodeURIComponent(q + ' filetype:bitmap')}`;
  for (let t = 0; t < 4; t++) {
    const r = await fetch(url, { headers: { 'User-Agent': 'catalog-seed/1.0' } });
    if (r.ok) {
      const d = await r.json();
      return Object.values(d.query?.pages ?? {})
        .filter(p => p.imageinfo?.[0].mime === 'image/jpeg' && p.imageinfo[0].width >= 600 && p.imageinfo[0].height >= 400)
        .sort((a, b) => a.index - b.index)
        .map(p => p.imageinfo[0].thumburl.split('?')[0]);
    }
    await sleep(2000 * (t + 1));
  }
  return [];
}

const esc = s => s.replace(/'/g, "''");
let sql = `-- Generated by gen-product-images.mjs. Sets thumbnail_url and replaces product_images
-- (ordered gallery) for every product, using images relevant to the product name.
BEGIN;\n`;
const missing = [];
for (const name of names) {
  const q = Q[name] ?? name;
  const urls = (await search(q)).slice(0, 4);
  await sleep(400);
  if (urls.length < 2) { missing.push(name); continue; }
  const n = esc(name);
  sql += `\n-- ${name}\nUPDATE product.products SET thumbnail_url = '${esc(urls[0])}', updated_at = now() WHERE name = '${n}';\n`;
  sql += `DELETE FROM product.product_images WHERE product_id IN (SELECT id FROM product.products WHERE name = '${n}');\n`;
  sql += `INSERT INTO product.product_images (product_id, url, display_order)\nSELECT p.id, v.url, v.ord FROM product.products p, (VALUES ${urls.map((u, i) => `('${esc(u)}', ${i})`).join(', ')}) AS v(url, ord) WHERE p.name = '${n}';\n`;
  console.error(`${name}: ${urls.length}`);
}
sql += '\nCOMMIT;\n';
writeFileSync(new URL('./update-product-images.sql', import.meta.url), sql);
console.error('MISSING:', missing);
