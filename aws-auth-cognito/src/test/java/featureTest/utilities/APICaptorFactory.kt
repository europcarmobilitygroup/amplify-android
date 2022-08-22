package featureTest.utilities

import com.amplifyframework.auth.AuthException
import com.amplifyframework.auth.result.AuthResetPasswordResult
import com.amplifyframework.auth.result.AuthSignUpResult
import com.amplifyframework.core.Consumer
import com.amplifyframework.testutils.featuretest.ExpectationShapes
import com.amplifyframework.testutils.featuretest.ResponseType
import com.amplifyframework.testutils.featuretest.auth.AuthAPI
import com.amplifyframework.testutils.featuretest.auth.AuthAPI.resetPassword
import com.amplifyframework.testutils.featuretest.auth.AuthAPI.signUp
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.CountDownLatch

/**
 * Factory with association of results captor to top level APIs
 */
class APICaptorFactory(
    private val authApi: ExpectationShapes.Amplify,
    private val latch: CountDownLatch, // ToDo: Remove this param
) {
    companion object {
        val onSuccess = mapOf(
            resetPassword to mockk<Consumer<AuthResetPasswordResult>>(),
            signUp to mockk<Consumer<AuthSignUpResult>>()
        )
        val onError = Consumer<Any> {}
        val captors: MutableMap<AuthAPI, CapturingSlot<*>> = mutableMapOf()
    }

    init {
        captors.clear()
        if (authApi.responseType == ResponseType.Success) setupOnSuccess()
        else setupOnError()
    }

    private fun setupOnSuccess() {
        when (val apiName = authApi.apiName) {
            resetPassword -> {
                val resultCaptor = slot<AuthResetPasswordResult>()
                val consumer = onSuccess[apiName] as Consumer<AuthResetPasswordResult>
                every { consumer.accept(capture(resultCaptor)) } answers { latch.countDown() }
                captors[apiName] = resultCaptor
            }
            signUp -> {
                val resultCaptor = slot<AuthSignUpResult>()
                val consumer = onSuccess[apiName] as Consumer<AuthSignUpResult>
                every { consumer.accept(capture(resultCaptor)) } answers { latch.countDown() }
                captors[apiName] = resultCaptor
            }
            else -> throw Error("onSuccess for $authApi is not defined!")
        }
    }

    private fun setupOnError() {
        val resultCaptor = slot<AuthException>()
        every { onError.accept(capture(resultCaptor)) } answers { latch.countDown() }
        captors[authApi.apiName] = resultCaptor
    }
}
