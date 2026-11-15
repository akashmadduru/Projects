/**
 * Razorpay Standard Checkout loader. The script is fetched on demand from Razorpay (never bundled)
 * the first time a customer picks Razorpay, so customers who pay by card elsewhere never load it.
 */

const SCRIPT_URL = 'https://checkout.razorpay.com/v1/checkout.js'

export interface RazorpaySuccess {
  razorpay_order_id: string
  razorpay_payment_id: string
  razorpay_signature: string
}

interface RazorpayOptions {
  key: string
  order_id: string
  name: string
  description?: string
  theme?: { color?: string }
  handler: (response: RazorpaySuccess) => void
  modal?: { ondismiss?: () => void }
}

interface RazorpayInstance {
  open: () => void
  on: (event: 'payment.failed', cb: (e: { error?: { description?: string } }) => void) => void
}

declare global {
  interface Window {
    Razorpay?: new (options: RazorpayOptions) => RazorpayInstance
  }
}

let loading: Promise<void> | null = null

export function loadRazorpay(): Promise<void> {
  if (window.Razorpay) return Promise.resolve()
  if (!loading) {
    loading = new Promise<void>((resolve, reject) => {
      const script = document.createElement('script')
      script.src = SCRIPT_URL
      script.async = true
      script.onload = () => resolve()
      script.onerror = () => {
        loading = null
        script.remove()
        reject(new Error('Could not load the payment window. Check your connection and try again.'))
      }
      document.head.appendChild(script)
    })
  }
  return loading
}

export interface CheckoutArgs {
  keyId: string
  orderId: string
  name: string
  description?: string
  onSuccess: (response: RazorpaySuccess) => void
  onDismiss: () => void
  onFailure: (message: string) => void
}

/** Opens the Razorpay payment window for an order the server created. */
export async function openRazorpayCheckout(args: CheckoutArgs): Promise<void> {
  await loadRazorpay()
  if (!window.Razorpay) throw new Error('The payment window is unavailable.')
  const instance = new window.Razorpay({
    key: args.keyId,
    order_id: args.orderId,
    name: args.name,
    description: args.description,
    theme: { color: '#1b7469' },
    handler: args.onSuccess,
    modal: { ondismiss: args.onDismiss },
  })
  // A failed attempt can be retried inside the same window; this only reports it.
  instance.on('payment.failed', (e) => args.onFailure(e.error?.description ?? 'The payment failed. Please try again.'))
  instance.open()
}
