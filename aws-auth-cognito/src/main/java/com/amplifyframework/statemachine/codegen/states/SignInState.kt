/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amplifyframework.statemachine.codegen.states

import com.amplifyframework.statemachine.State
import com.amplifyframework.statemachine.StateMachineEvent
import com.amplifyframework.statemachine.StateMachineResolver
import com.amplifyframework.statemachine.StateResolution
import com.amplifyframework.statemachine.codegen.actions.SignInActions
import com.amplifyframework.statemachine.codegen.events.SignInEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
sealed class SignInState : State {
    data class NotStarted(val id: String = "") : SignInState()
    @Serializable
    data class SigningInWithSRP(override var srpSignInState: SRPSignInState?) : SignInState()
    data class SigningInWithHostedUI(val id: String = "") : SignInState()
    data class SigningInWithCustom(val id: String = "") : SignInState()
    data class SigningInWithSRPCustom(val id: String = "") : SignInState()
    data class ResolvingChallenge(override var challengeState: SignInChallengeState?) : SignInState()
    data class Done(val id: String = "") : SignInState()
    data class Error(val exception: Exception) : SignInState()

    @Transient
    open var srpSignInState: SRPSignInState? = SRPSignInState.NotStarted()
    open var challengeState: SignInChallengeState? = SignInChallengeState.NotStarted()

    class Resolver(
        private val srpSignInResolver: StateMachineResolver<SRPSignInState>,
        private val challengeResolver: StateMachineResolver<SignInChallengeState>,
        private val signInActions: SignInActions
    ) :
        StateMachineResolver<SignInState> {
        override val defaultState = NotStarted()

        private fun asSignInEvent(event: StateMachineEvent): SignInEvent.EventType? {
            return (event as? SignInEvent)?.eventType
        }

        override fun resolve(oldState: SignInState, event: StateMachineEvent): StateResolution<SignInState> {
            val resolution = resolveSignInEvent(oldState, event)
            val actions = resolution.actions.toMutableList()
            val builder = Builder(resolution.newState)

            oldState.srpSignInState?.let { srpSignInResolver.resolve(it, event) }?.let {
                builder.srpSignInState = it.newState
                actions += it.actions
            }

            oldState.challengeState?.let { challengeResolver.resolve(it, event) }?.let {
                builder.challengeState = it.newState
                actions += it.actions
            }

            return StateResolution(builder.build(), actions)
        }

        private fun resolveSignInEvent(
            oldState: SignInState,
            event: StateMachineEvent
        ): StateResolution<SignInState> {
            val signInEvent = asSignInEvent(event)
            val defaultResolution = StateResolution(oldState)
            return when (oldState) {
                is NotStarted -> when (signInEvent) {
                    is SignInEvent.EventType.InitiateSignInWithSRP -> StateResolution(
                        SigningInWithSRP(oldState.srpSignInState),
                        listOf(signInActions.startSRPAuthAction(signInEvent))
                    )
                    else -> defaultResolution
                }
                is SigningInWithSRP -> when (signInEvent) {
                    is SignInEvent.EventType.ReceivedChallenge -> {
                        val action = signInActions.initResolveChallenge(signInEvent)
                        StateResolution(ResolvingChallenge(SignInChallengeState.NotStarted()), listOf(action))
                    }
                    is SignInEvent.EventType.ThrowError -> StateResolution(Error(signInEvent.exception), listOf())
                    else -> defaultResolution
                }
                else -> defaultResolution
            }
        }
    }

    class Builder(private val signInState: SignInState) :
        com.amplifyframework.statemachine.Builder<SignInState> {
        var srpSignInState: SRPSignInState? = null
        var challengeState: SignInChallengeState? = null

        override fun build(): SignInState = when (signInState) {
            is SigningInWithSRP -> SigningInWithSRP(srpSignInState)
            is ResolvingChallenge -> ResolvingChallenge(challengeState)
            else -> signInState
        }
    }
}
