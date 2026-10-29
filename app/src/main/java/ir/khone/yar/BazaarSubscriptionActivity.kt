package ir.khone.yar

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest

class BazaarSubscriptionActivity : ComponentActivity() {
    private var payment: Payment? = null
    private var connection: ir.cafebazaar.poolakey.Connection? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rsa = BuildConfig.BAZAAR_RSA_PUBLIC_KEY.trim()
        if (rsa.isEmpty()) {
            fail("کلید عمومی بازار در تنظیمات بیلد ثبت نشده است.")
            return
        }

        val config = PaymentConfiguration(
            localSecurityCheck = SecurityCheck.Enable(rsaPublicKey = rsa),
            shouldSupportSubscription = true
        )
        val billing = Payment(context = this, config = config)
        payment = billing
        connection = billing.connect {
            connectionSucceed {
                checkSubscription(billing)
            }
            connectionFailed { error ->
                runOnUiThread { fail("اتصال به بازار برقرار نشد: ${error.message ?: "خطای نامشخص"}") }
            }
            disconnected { }
        }
    }

    private fun checkSubscription(billing: Payment) {
        billing.getSubscribedProducts {
            querySucceed { purchases ->
                val active = purchases.any { it.productId == BuildConfig.BAZAAR_SUBSCRIPTION_ID }
                getSharedPreferences("oba_subscription", MODE_PRIVATE).edit()
                    .putBoolean("active", active)
                    .putLong("checked_at", System.currentTimeMillis())
                    .apply()
                if (active) {
                    runOnUiThread {
                        Toast.makeText(this@BazaarSubscriptionActivity, "اشتراک اوبا فعال است.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                } else {
                    runOnUiThread { startSubscription(billing) }
                }
            }
            queryFailed { error ->
                runOnUiThread { fail("بررسی اشتراک انجام نشد: ${error.message ?: "خطای نامشخص"}") }
            }
        }
    }

    private fun startSubscription(billing: Payment) {
        val request = PurchaseRequest(
            productId = BuildConfig.BAZAAR_SUBSCRIPTION_ID,
            requestCode = 7101,
            payload = "oba_subscription",
            dynamicPriceToken = null
        )
        billing.subscribeProduct(
            registry = activityResultRegistry,
            request = request
        ) {
            purchaseFlowBegan { }
            failedToBeginFlow { error ->
                runOnUiThread { fail("بازکردن صفحهٔ پرداخت ناموفق بود: ${error.message ?: "خطای نامشخص"}") }
            }
            purchaseSucceed { purchase ->
                if (purchase.productId == BuildConfig.BAZAAR_SUBSCRIPTION_ID) {
                    getSharedPreferences("oba_subscription", MODE_PRIVATE).edit()
                        .putBoolean("active", true)
                        .putLong("checked_at", System.currentTimeMillis())
                        .putString("purchase_token", purchase.purchaseToken)
                        .apply()
                    runOnUiThread {
                        Toast.makeText(this@BazaarSubscriptionActivity, "پرداخت موفق بود؛ اشتراک اوبا فعال شد.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                } else {
                    runOnUiThread { fail("شناسهٔ محصول با اشتراک اوبا مطابقت ندارد.") }
                }
            }
            purchaseCanceled {
                runOnUiThread { fail("خرید لغو شد.") }
            }
            purchaseFailed { error ->
                runOnUiThread { fail("پرداخت ناموفق بود: ${error.message ?: "خطای نامشخص"}") }
            }
        }
    }

    private fun fail(message: String) {
        if (finished || isFinishing) return
        finished = true
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        connection?.disconnect()
        connection = null
        payment = null
        super.onDestroy()
    }
}
