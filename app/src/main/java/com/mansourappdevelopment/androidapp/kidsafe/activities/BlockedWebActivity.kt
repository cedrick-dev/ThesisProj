package com.mansourappdevelopment.androidapp.kidsafe.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mansourappdevelopment.androidapp.kidsafe.R

class BlockedWebActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked_web)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }
}
