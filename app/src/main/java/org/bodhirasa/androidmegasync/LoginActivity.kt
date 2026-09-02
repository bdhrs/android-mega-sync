package org.bodhirasa.androidmegasync

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val session = SessionStore(this)
        if (session.token != null) {
            goToMain()
            return
        }

        setContentView(R.layout.activity_login)

        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val loginButton = findViewById<Button>(R.id.login)

        loginButton.setOnClickListener {
            loginButton.isEnabled = false
            val e = email.text.toString()
            val p = password.text.toString()
            Thread {
                val result = runCatching {
                    MegaClientProvider.get(this).login(e, p)
                }
                runOnUiThread {
                    result.fold(
                        onSuccess = { token ->
                            session.token = token
                            Toast.makeText(this, "Logged in", Toast.LENGTH_SHORT).show()
                            goToMain()
                        },
                        onFailure = {
                            loginButton.isEnabled = true
                            Toast.makeText(this, "Login failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }.start()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
