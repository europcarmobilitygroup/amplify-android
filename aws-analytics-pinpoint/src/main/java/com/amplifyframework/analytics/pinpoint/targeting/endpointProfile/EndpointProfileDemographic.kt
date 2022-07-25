/*
 *  Copyright 2016-2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License").
 *  You may not use this file except in compliance with the License.
 *  A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 *
 */
package com.amplifyframework.analytics.pinpoint.targeting.endpointProfile

import android.content.Context
import android.os.Build
import com.amplifyframework.analytics.pinpoint.internal.core.system.AndroidSystem
import com.amplifyframework.analytics.pinpoint.internal.core.util.JSONSerializable
import com.amplifyframework.analytics.pinpoint.internal.core.util.JSONBuilder
import org.json.JSONObject
import java.util.*

class EndpointProfileDemographic(system: AndroidSystem, applicationContext: Context) :
    JSONSerializable {
    internal var make = system.getDeviceDetails().manufacturer
    fun getMake() = make
    fun setMake(make: String) = make.also { this.make = it }

    internal var model: String = Build.MODEL
    fun getModel() = model
    fun setModel(model: String) = model.also { this.model = it }

    internal var timezone: String = TimeZone.getDefault().id
    fun getTimezone() = timezone
    fun setTimezone(timezone: String) = timezone.also { this.timezone = it }

    internal var locale: Locale = applicationContext.resources.configuration.locales[0]
    fun getLocale() = locale
    fun setLocale(locale: Locale) = locale.also { this.locale = it }

    internal var appVersion = system.getAppDetails().versionName()!!
    fun getAppVersion() = appVersion
    fun setAppVersion(appVersion: String) = appVersion.also { this.appVersion = it }

    internal var platform = ENDPOINT_PLATFORM
    fun getPlatform() = platform
    fun setPlatform(platform: String) = platform.also { this.platform = it }

    internal var platformVersion: String = Build.VERSION.RELEASE
    fun getPlatformVersion() = platformVersion
    fun setPlatformVersion(platformVersion: String) = platformVersion.also { this.platformVersion = it }

    override fun toJSONObject(): JSONObject {
        val builder = JSONBuilder(null)
        builder.withAttribute("Make", make)
        builder.withAttribute("Model", model)
        builder.withAttribute("Timezone", timezone)
        builder.withAttribute("Locale", locale)
        builder.withAttribute("AppVersion", appVersion)
        builder.withAttribute("Platform", platform)
        builder.withAttribute("PlatformVersion", platformVersion)
        return builder.toJSONObject()
    }

    companion object {
        /**
         * Android platform.
         */
        const val ENDPOINT_PLATFORM = "ANDROID"
    }
}