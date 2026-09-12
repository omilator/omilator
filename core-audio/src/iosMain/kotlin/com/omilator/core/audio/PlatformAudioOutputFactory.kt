package com.omilator.core.audio

private class IosFactory : AudioOutputFactory {
    override fun create(): AudioOutput = IosAudioOutput()
}

actual fun createAudioOutputFactory(): AudioOutputFactory = IosFactory()
