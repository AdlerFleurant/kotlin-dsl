/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.kotlin.dsl.provider

import org.gradle.api.internal.initialization.ClassLoaderScope

import org.gradle.cache.internal.CrossBuildInMemoryCache
import org.gradle.cache.internal.CrossBuildInMemoryCacheFactory

import org.gradle.internal.Cast.uncheckedCast
import org.gradle.internal.classloader.ClasspathHasher
import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.classpath.DefaultClassPath
import org.gradle.internal.hash.HashCode

import org.gradle.kotlin.dsl.support.loggerFor

import java.io.File

import java.lang.ref.WeakReference

import javax.inject.Inject


internal
data class LoadedScriptClass<out T>(
    val compiledScript: CompiledScript<T>,
    val scriptClass: Class<*>
)


private
val logger = loggerFor<KotlinScriptPluginFactory>()


internal
class KotlinScriptClassloadingCache @Inject constructor(
    cacheFactory: CrossBuildInMemoryCacheFactory,
    private val classpathHasher: ClasspathHasher
) {

    private
    val cache: CrossBuildInMemoryCache<ScriptCacheKey, LoadedScriptClass<*>> = cacheFactory.newCache()

    fun <T> loadScriptClass(
        scriptBlock: ScriptBlock<T>,
        parentClassLoader: ClassLoader,
        compile: (ScriptBlock<T>) -> CompiledScript<T>,
        createClassLoaderScope: () -> ClassLoaderScope,
        additionalClassPath: ClassPath = ClassPath.EMPTY
    ): LoadedScriptClass<T> {

        val key = cacheKeyFor(scriptBlock, parentClassLoader, additionalClassPath)
        val cached = get(key)
        if (cached != null) {
            return uncheckedCast(cached)
        }

        val compiledScript = compile(scriptBlock)

        logClassloadingOf(scriptBlock)
        val scriptClass = classFrom(compiledScript, createClassLoaderScope())

        return LoadedScriptClass(compiledScript, scriptClass).also {
            put(key, it)
        }
    }

    fun get(key: ScriptCacheKey): LoadedScriptClass<*>? =
        cache.get(key)

    fun <T> put(key: ScriptCacheKey, loadedScriptClass: LoadedScriptClass<T>) {
        cache.put(key, loadedScriptClass)
    }

    private
    fun <T> cacheKeyFor(
        scriptBlock: ScriptBlock<T>,
        parentClassLoader: ClassLoader,
        additionalClassPath: ClassPath
    ) =

        ScriptCacheKey(
            scriptBlock.scriptTemplate.qualifiedName!!,
            scriptBlock.sourceHash,
            parentClassLoader,
            lazy { classpathHasher.hash(additionalClassPath) })

    private
    fun classFrom(compiledScript: CompiledScript<*>, scope: ClassLoaderScope): Class<*> =
        classLoaderFor(compiledScript.location, scope)
            .loadClass(compiledScript.className)

    private
    fun classLoaderFor(location: File, scope: ClassLoaderScope) =
        scope
            .local(DefaultClassPath.of(location))
            .lock()
            .localClassLoader

    private
    fun <T> logClassloadingOf(scriptBlock: ScriptBlock<T>) =
        logger.debug("Loading {} from {}", scriptBlock.scriptTemplate.simpleName, scriptBlock.displayName)
}


internal
class ScriptCacheKey(
    private val templateId: String,
    private val sourceHash: HashCode,
    parentClassLoader: ClassLoader,
    private val classPathHash: Lazy<HashCode>
) {

    private
    val parentClassLoader = WeakReference(parentClassLoader)

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        val that = other as? ScriptCacheKey ?: return false
        val thisParentLoader = parentClassLoader.get()
        return thisParentLoader != null
            && thisParentLoader == that.parentClassLoader.get()
            && templateId == that.templateId
            && sourceHash == that.sourceHash
            && classPathHash.value == that.classPathHash.value
    }

    override fun hashCode(): Int {
        var result = templateId.hashCode()
        result = 31 * result + sourceHash.hashCode()
        parentClassLoader.get()?.let { loader ->
            result = 31 * result + loader.hashCode()
        }
        return result
    }
}
