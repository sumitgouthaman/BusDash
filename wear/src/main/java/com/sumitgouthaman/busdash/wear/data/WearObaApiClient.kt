package com.sumitgouthaman.busdash.wear.data

import android.content.Context
import android.net.ConnectivityManager
import okhttp3.Dns
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress

object WearObaApiClient {
    fun create(baseUrl: String, context: Context): OneBusAwayApi {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return cm.activeNetwork?.getAllByName(hostname)?.toList()
                    ?: InetAddress.getAllByName(hostname).toList()
            }
        }
        val okHttpClient = OkHttpClient.Builder()
            .dns(dns)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OneBusAwayApi::class.java)
    }
}
