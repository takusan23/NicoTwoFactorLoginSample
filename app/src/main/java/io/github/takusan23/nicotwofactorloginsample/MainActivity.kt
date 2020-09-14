package io.github.takusan23.nicotwofactorloginsample

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {

    private val okHttpClient = OkHttpClient().newBuilder().apply {
        // リダイレクトを禁止する
        followRedirects(false)
        followSslRedirects(false)
    }.build()

    /** 二段階認証APIのURL */
    private var twoFactorAPIURL = ""

    /**
     * ログインで何回かAPIを叩くけど、その際に共通で指定するCookie。
     * mfa_session と nicosid が必要
     * */
    var loginCookie = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        login_button.setOnClickListener {
            lifecycleScope.launch {
                val response = postLogin("めあど", "ぱすわーど")
                // ユーザーセッションがあれば二段階認証ではない
                if (response.headers.find { pair -> pair.second.contains("user_session=user_session") } != null) {
                    println("二段階認証では有りません")
                    println("ユーザーセッション：${parseUserSession(response)}")
                } else {
                    twoFactorAPIURL = getTwoFactorAPIURL(response.headers["Location"]!!)
                }
            }
        }

        two_factor_login.setOnClickListener {
            // ワンタイムパスワードの値取得
            val otp = one_time_password_edittext.text.toString()
            lifecycleScope.launch {
                val location = postOneTimePassword(twoFactorAPIURL, otp) ?: return@launch
                val userSession = getUserSession(location)
                println("おわり。ユーザーセッション：$userSession")
            }
        }

    }

    /**
     * niconicoへログインする関数
     * */
    private suspend fun postLogin(mail: String, pass: String) = withContext(Dispatchers.Default) {
        val url =
            "https://account.nicovideo.jp/login/redirector"
        val postData = "mail_tel=$mail&password=$pass"
        val request = Request.Builder().apply {
            url(url)
            addHeader("User-Agent", "TatimiDroid;@takusan_23")
            post(postData.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())) // 送信するデータ。
        }.build()
        println("ログイン開始：$url")
        val response = okHttpClient.newCall(request).execute()
        // Set-Cookieを解析
        var mfaSession = ""
        var nicosid = ""
        response.headers.forEach {
            // Set-Cookie に入ってる mfa_session と nicosid を控える
            if (it.first == "Set-Cookie") {
                if (it.second.contains("mfa_session")) {
                    mfaSession = it.second.split(";")[0]
                }
                if (it.second.contains("nicosid")) {
                    nicosid = it.second.split(";")[0]
                }
            }
        }
        // これからの通信で使うCookieを作成
        loginCookie = "$mfaSession;$nicosid"
        response
    }

    /**
     * 二段階認証のWebページへアクセスして、認証コードを送るAPIのURLを取り出す
     * @param location ログインAPIのレスポンスヘッダーのLocation
     * @return 二段階認証APIのURL
     * */
    private suspend fun getTwoFactorAPIURL(location: String) = withContext(Dispatchers.Default) {
        println("二段階認証APIのURL取得API：$location")
        val request = Request.Builder().apply {
            url(location)
            addHeader("User-Agent", "TatimiDroid;@takusan_23")
            addHeader("Cookie", loginCookie)
            get()
        }.build()
        val response = okHttpClient.newCall(request).execute()
        println("二段階認証APIのURL取得API ステータスコード：${response.code}")
        val responseString = response.body?.string()
        // HTML内からURLを探す
        val document = Jsoup.parse(responseString)
        val path = document.getElementsByTag("form")[0].attr("action")
        // 二段階認証をするAPIのURLを返す
        "https://account.nicovideo.jp$path"
    }

    /**
     * ワンタイムパスワードを入れて二段階認証を完了させる関数
     * @param otp メールで送られてくる認証コード
     * @param twoFactorAPIURL [getTwoFactorAPIURL]の戻り値
     * @return 最後に叩くAPIのURL。叩くと、ユーザーセッションが手に入る。
     * */
    private suspend fun postOneTimePassword(twoFactorAPIURL: String, otp: String) = withContext(Dispatchers.Default) {
        println("二段階認証API叩く：$twoFactorAPIURL")
        val formData = FormBody.Builder().apply {
            add("otp", otp) // メールで送られてきた認証コード
            add("loginBtn", "ログイン")
            add("device_name", "Android") // デバイス名
        }.build()
        val request = Request.Builder().apply {
            url(twoFactorAPIURL)
            addHeader("User-Agent", "TatimiDroid;@takusan_23")
            addHeader("Cookie", loginCookie)
            post(formData)
        }.build()
        val response = okHttpClient.newCall(request).execute()
        println("二段階認証API ステータスコード：${response.code}")
        response.headers["Location"]
    }

    /**
     * 最後。ユーザーセッションを取得する
     * @param location [postOneTimePassword]の戻り値
     * @return ユーザーセッション
     * */
    private suspend fun getUserSession(location: String) = withContext(Dispatchers.Default) {
        val url = location // URLを完成させる
        println("ユーザーセッション取得：$url")
        val request = Request.Builder().apply {
            url(url)
            addHeader("User-Agent", "TatimiDroid;@takusan_23")
            addHeader("Cookie", loginCookie)
            get()
        }.build()
        val response = okHttpClient.newCall(request).execute()
        println("ユーザーセッション取得 ステータスコード：${response.code}")
        parseUserSession(response)
    }

    /**
     * レスポンスヘッダーからユーザーセッションを取り出す
     * */
    private fun parseUserSession(response: Response): String {
        return response.headers.filter { pair -> pair.second.contains("user_session") }[1].second.split(";")[0]
    }

}