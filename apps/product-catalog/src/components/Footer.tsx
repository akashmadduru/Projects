/**
 * Footer component - clean minimalist design
 */

export function Footer() {
  return (
    <footer className="border-t border-outline/10 mt-auto bg-surface/50 backdrop-blur-sm">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
          <div>
            <h3 className="font-semibold text-on-surface mb-4">About</h3>
            <ul className="space-y-2 text-sm text-on-surface-muted">
              <li>
                <a href="#" className="hover:text-on-surface transition-colors">
                  About Us
                </a>
              </li>
              <li>
                <a href="#" className="hover:text-on-surface transition-colors">
                  Blog
                </a>
              </li>
            </ul>
          </div>
          <div>
            <h3 className="font-semibold text-on-surface mb-4">Support</h3>
            <ul className="space-y-2 text-sm text-on-surface-muted">
              <li>
                <a href="#" className="hover:text-on-surface transition-colors">
                  Contact
                </a>
              </li>
              <li>
                <a href="#" className="hover:text-on-surface transition-colors">
                  FAQ
                </a>
              </li>
            </ul>
          </div>
          <div>
            <h3 className="font-semibold text-on-surface mb-4">Legal</h3>
            <ul className="space-y-2 text-sm text-on-surface-muted">
              <li>
                <a href="#" className="hover:text-on-surface transition-colors">
                  Privacy
                </a>
              </li>
              <li>
                <a href="#" className="hover:text-on-surface transition-colors">
                  Terms
                </a>
              </li>
            </ul>
          </div>
        </div>
        <div className="border-t border-outline/10 mt-8 pt-8">
          <p className="text-center text-sm text-on-surface-muted">
            &copy; 2024 Product Catalog. All rights reserved.
          </p>
        </div>
      </div>
    </footer>
  )
}
