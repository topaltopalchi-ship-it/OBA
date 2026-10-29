package ir.khone.yar

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import ir.cafebazaar.poolakey.Payment
import ir.cafebazaar.poolakey.config.PaymentConfiguration
import ir.cafebazaar.poolakey.config.SecurityCheck
import ir.cafebazaar.poolakey.request.PurchaseRequest
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class BazaarSubscriptionActivity : ComponentActivity() {
    private var payment: Payment? = null
    private var connection: ir.cafebazaar.poolakey.Connection? = null
    private var finished = false
    private val apiUrl = "https://sxyaujvliummbvzznejq.supabase.co"
    private val apiKey = "sb_publishable_AQdI9Ehm-lf7p1Rs7bu4BA_m60TTlen"
    private var accessToken = ""
    private var userId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val auth = getSharedPreferences("oba_auth", MODE_PRIVATE)
        if (auth.getString("mode", "guest") != "account") {
            fail("برای خرید و بازیابی اشتراک، ابتدا وارد حساب اوبا شو.")
            return
        }
        userId = auth.getString("user_id", "") ?: ""
        accessToken = auth.getString("access_token", "") ?: ""
        val expiresAt = auth.getLong("expires_at", 0L)
        if (userId.isBlank() || accessToken.isBlank() || expiresAt <= System.currentTimeMillis() + 60000L) {
            fail("نشست حساب منقضی شده است؛ ابتدا خارج شو و دوباره وارد حساب اوبا شو.")
            return
        }

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
            connectionSucceed { checkSubscription(billing) }
            connectionFailed { error ->
                runOnUiThread { fail("اتصال به بازار برقرار نشد: ${error.message ?: "خطای نامشخص"}") }
            }
            disconnected { }
        }
    }

    private fun checkSubscription(billing: Payment) {
        billing.getSubscribedProducts {
            querySucceed { purchases ->
                val owned = purchases.firstOrNull { it.productId == BuildConfig.BAZAAR_SUBSCRIPTION_ID }
                if (owned != null && owned.purchaseToken.isNotBlank()) {
                    verifyOnServer(owned.purchaseToken) { active, message ->
                        if (active) {
                            runOnUiThread {
                                Toast.makeText(this@BazaarSubscriptionActivity, "اشتراک معتبر به حساب اوبا وصل شد.", Toast.LENGTH_LONG).show()
                                finish()
                            }
                        } else {
                            runOnUiThread { startSubscription(billing, message) }
                        }
                    }
                } else {
                    verifyOnServer("") { active, _ ->
                        if (active) {
                            runOnUiThread {
                                Toast.makeText(this@BazaarSubscriptionActivity, "اشتراک حساب بازیابی شد.", Toast.LENGTH_LONG).show()
                                finish()
                            }
                        } else {
                            runOnUiThread { startSubscription(billing, null) }
                        }
                    }
                }
            }
            queryFailed { error ->
                runOnUiThread { fail("بررسی خریدهای بازار انجام نشد: ${error.message ?: "خطای نامشخص"}") }
            }
        }
    }

    private fun startSubscription(billing: Payment, previousMessage: String?) {
        val request = PurchaseRequest(
            productId = BuildConfig.BAZAAR_SUBSCRIPTION_ID,
            requestCode = 7101,
            payload = "oba_user:$userId",
            dynamicPriceToken = null
        )
        billing.subscribeProduct(registry = activityResultRegistry, request = request) {
            purchaseFlowBegan { }
            failedToBeginFlow { error ->
                runOnUiThread { fail("بازکردن صفحهٔ پرداخت ناموفق بود: ${error.message ?: "خطای نامشخص"}") }
            }
            purchaseSucceed { purchase ->
                if (purchase.productId != BuildConfig.BAZAAR_SUBSCRIPTION_ID || purchase.purchaseToken.isBlank()) {
                    runOnUiThread { fail("اطلاعات خرید با اشتراک اوبا مطابقت ندارد.") }
                } else {
                    verifyOnServer(purchase.purchaseToken) { active, message ->
                        runOnUiThread {
                            if (active) {
                                Toast.makeText(this@BazaarSubscriptionActivity, "خرید تأیید شد و اشتراک به حساب اوبا وصل شد.", Toast.LENGTH_LONG).show()
                                finish()
                            } else {
                                fail(message ?: "بازار هنوز اشتراک را تأیید نکرده است؛ دسترسی ویژه فعال نشد.")
                            }
                        }
                    }
                }
            }
            purchaseCanceled { runOnUiThread { fail("خرید لغو شد.") } }
            purchaseFailed { error ->
                runOnUiThread { fail("پرداخت ناموفق بود: ${error.message ?: "خطای نامشخص"}") }
            }
        }
    }

    private fun verifyOnServer(purchaseToken: String, callback: (Boolean, String?) -> Unit) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject().put("productId", BuildConfig.BAZAAR_SUBSCRIPTION_ID)
                if (purchaseToken.isNotBlank()) body.put("purchaseToken", purchaseToken)
                conn = URL("$apiUrl/functions/v1/verify-bazaar-subscription").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("apikey", apiKey)
                conn.setRequestProperty("Authorization", "Bearer $accessToken")
                conn.setRequestProperty("Accept", "application/json")
                try {
                    conn.outputStream.use { out: OutputStream ->
                        out.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                } catch (e: Exception) {
                    throw e
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val result = try { JSONObject(responseText) } catch (_: Exception) { JSONObject() }
                if (status !in 200..299) {
                    callback(false, result.optString("error").ifBlank { "تأیید اشتراک از سرور انجام نشد." })
                } else {
                    callback(result.optBoolean("active", false), if (result.has("error")) result.optString("error") else null)
                }
            } catch (e: Exception) {
                callback(false, "ارتباط با سرور برای تأیید خرید برقرار نشد؛ دسترسی ویژه فعال نشد.")
            } finally {
                conn?.disconnect()
            }
        }.start()
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
