package io.throneproj.throne.bg

import java.io.Closeable

interface AbstractInstance : Closeable {

    fun launch()

}