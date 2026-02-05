package com.duckflix.lite.di

import android.content.Context
import com.duckflix.lite.data.remote.AuthInterceptor
import com.duckflix.lite.data.remote.DuckFlixApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val cacheSize = 10 * 1024 * 1024L // 10 MB
        val cache = Cache(File(context.cacheDir, "http_cache"), cacheSize)

        val logging = HttpLoggingInterceptor { message ->
            // Add marker for logo-related responses
            if (message.contains("logoPath") || message.contains("/search/tmdb/")) {
                println("[LOGO-DEBUG-HTTP] $message")
            } else {
                println(message)
            }
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val authInterceptor = AuthInterceptor(context)

        // Trust all certificates (kept for compatibility, lite.duckflix.tv has Let's Encrypt cert)
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        return OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)  // Increased to 90s for stream polling
            .writeTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        @ApplicationContext context: Context,
        moshi: Moshi,
        okHttpClient: OkHttpClient
    ): Retrofit {
        // Single server URL - always use public domain
        val baseUrl = "https://lite.duckflix.tv/api/"
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideDuckFlixApi(retrofit: Retrofit): DuckFlixApi =
        retrofit.create(DuckFlixApi::class.java)
}
