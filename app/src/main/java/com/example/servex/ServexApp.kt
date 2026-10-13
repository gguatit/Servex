package com.example.servex

import android.app.Application
import com.example.servex.di.AppContainer

class ServexApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
