package com.devanshu.devnex.core.network

import android.content.Context
import com.devanshu.devnex.BuildConfig
import com.devanshu.devnex.core.database.DevNexDatabase
import com.devanshu.devnex.core.security.KeystoreManager
import com.devanshu.devnex.data.demo.DemoRemoteDataSource
import com.devanshu.devnex.data.remote.RemoteDataSource
import com.devanshu.devnex.data.remote.SupabaseRemoteDataSource
import com.devanshu.devnex.data.repository.*
import com.devanshu.devnex.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    val isSupabaseConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /** Supabase when keys are present in local.properties; otherwise the built-in Demo backend. */
    @Provides @Singleton
    fun remote(): RemoteDataSource =
        if (!isSupabaseConfigured) DemoRemoteDataSource()
        else SupabaseRemoteDataSource(
            createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
                defaultSerializer = KotlinXSerializer(Json { ignoreUnknownKeys = true })
                install(Auth)
                install(Postgrest)
                install(Realtime)
            }
        )

    @Provides @Singleton
    fun keystore(@ApplicationContext context: Context) = KeystoreManager(context)

    @Provides @Singleton
    fun database(@ApplicationContext context: Context, keystore: KeystoreManager) = DevNexDatabase.build(context, keystore)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun auth(impl: AuthRepositoryImpl): AuthRepository
    @Binds abstract fun workspace(impl: WorkspaceRepositoryImpl): WorkspaceRepository
    @Binds abstract fun tasks(impl: TaskRepositoryImpl): TaskRepository
    @Binds abstract fun team(impl: TeamRepositoryImpl): TeamRepository
    @Binds abstract fun chat(impl: ChatRepositoryImpl): ChatRepository
    @Binds abstract fun notifications(impl: NotificationRepositoryImpl): NotificationRepository
    @Binds abstract fun coverage(impl: CoverageRepositoryImpl): CoverageRepository
    @Binds abstract fun platform(impl: PlatformRepositoryImpl): PlatformRepository
    @Binds abstract fun modules(impl: ModulesRepositoryImpl): ModulesRepository
    @Binds abstract fun admin(impl: AdminRepositoryImpl): AdminRepository
}
