package org.sunsetware.phocid

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.*
import org.junit.Test
import org.sunsetware.phocid.utils.AsyncCache
import org.sunsetware.phocid.utils.Random

class AsyncCacheTest {
    @Test
    fun testBasicFunctionality() {
        runBlocking {
            val cache = AsyncCache<Int, Int>(this, 4)

            for (i in 0..<100) {
                assertThat(cache.getOrPut(i) { i }).isEqualTo(i)
            }
            for (i in 0..<100) {
                assertThat(cache.getOrPut(i) { 0 }).isEqualTo(0)
            }
        }
    }

    @Test
    fun testNoDuplicateCreation() {
        repeat(10) {
            runBlocking {
                val cache = AsyncCache<Int, Int>(this, 4)

                val createCount = AtomicInteger(0)
                repeat(100) {
                    cache.getOrPut(0) {
                        Thread.sleep(Random.nextLong(0, 100))
                        createCount.incrementAndGet()
                    }
                }

                assertThat(createCount.get()).isEqualTo(1)
                assertThat(cache.getOrPut(0) { 0 }).isEqualTo(1)
            }
        }
    }

    @Test
    fun testEviction() {
        repeat(10) {
            runBlocking {
                val cache = AsyncCache<Int, Int>(this, 4)

                for (i in 0..<100) {
                    cache.getOrPut(i) { i }
                }

                delay(100)

                assertThat(cache.actualSize).isBetween(4, 5)

                for (i in 100 - 4..<100) {
                    assertThat(cache.get(i)).isNotNull()
                }
                assertThat(cache.get(0)).isNull()
            }
        }
    }

    @Test
    fun testRenewal() {
        repeat(10) {
            runBlocking {
                val cache = AsyncCache<Int, Int>(this, 4)

                for (i in 0..<100) {
                    cache.getOrPut(i) { i }
                    cache.get(0)
                }

                delay(100)

                assertThat(cache.get(0)).isNotNull()
            }

            runBlocking {
                val cache = AsyncCache<Int, Int>(this, 4)

                for (i in 0..<100) {
                    cache.getOrPut(i) { i }
                    cache.getOrPut(0) { i }
                }

                delay(100)

                assertThat(cache.get(0)).isEqualTo(0)
            }
        }
    }

    @Test
    fun testFailedCreationDoesNotPoisonTheKey() {
        runBlocking {
            val cache = AsyncCache<Int, Int>(this, 4)
            var attempts = 0

            runCatching {
                cache.getOrPut(0) {
                    attempts++
                    throw RuntimeException("creation failed")
                }
            }

            // The failed job must have been removed from the creation map, so a later creation
            // gets a fresh job instead of awaiting the dead one forever.
            assertThat(cache.getOrPut(0) { attempts++; 42 }).isEqualTo(42)
            assertThat(attempts).isEqualTo(2)
            assertThat(cache.get(0)).isEqualTo(42)
        }
    }
}
