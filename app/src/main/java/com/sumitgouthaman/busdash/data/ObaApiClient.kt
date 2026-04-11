package com.sumitgouthaman.busdash.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ObaApiClient {
    fun create(baseUrl: String): OneBusAwayApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OneBusAwayApi::class.java)
}
