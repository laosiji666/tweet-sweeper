package com.yg.tweetsweeper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.yg.tweetsweeper.ui.AppRoot
import com.yg.tweetsweeper.ui.TweetSweeperTheme
import com.yg.tweetsweeper.vm.MainViewModel

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.handleCallback(intent)
        setContent {
            TweetSweeperTheme {
                AppRoot(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        vm.handleCallback(intent)
    }
}
