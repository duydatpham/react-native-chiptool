package com.duydatpham.chiptool

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import java.util.Arrays

class ChiptoolPackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext) =
            listOf(ChiptoolModule(reactContext))

    override fun createViewManagers(reactContext: ReactApplicationContext) =
            emptyList<ViewManager<*, *>>()
}
