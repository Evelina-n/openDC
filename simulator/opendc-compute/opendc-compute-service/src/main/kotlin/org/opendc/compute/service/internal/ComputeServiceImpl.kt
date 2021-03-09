/*
 * Copyright (c) 2021 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.compute.service.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import mu.KotlinLogging
import org.opendc.compute.api.*
import org.opendc.compute.service.ComputeService
import org.opendc.compute.service.ComputeServiceEvent
import org.opendc.compute.service.driver.Host
import org.opendc.compute.service.driver.HostListener
import org.opendc.compute.service.driver.HostState
import org.opendc.compute.service.events.*
import org.opendc.compute.service.scheduler.AllocationPolicy
import org.opendc.trace.core.EventTracer
import org.opendc.utils.TimerScheduler
import org.opendc.utils.flow.EventFlow
import java.time.Clock
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Internal implementation of the OpenDC Compute service.
 *
 * @param context The [CoroutineContext] to use.
 * @param clock The clock instance to keep track of time.
 */
public class ComputeServiceImpl(
    private val context: CoroutineContext,
    private val clock: Clock,
    private val tracer: EventTracer,
    private val allocationPolicy: AllocationPolicy,
    private val schedulingQuantum: Long
) : ComputeService, HostListener {
    /**
     * The [CoroutineScope] of the service bounded by the lifecycle of the service.
     */
    private val scope = CoroutineScope(context)

    /**
     * The logger instance of this server.
     */
    private val logger = KotlinLogging.logger {}

    /**
     * The [Random] instance used to generate unique identifiers for the objects.
     */
    private val random = Random(0)

    /**
     * A mapping from host to host view.
     */
    private val hostToView = mutableMapOf<Host, HostView>()

    /**
     * The available hypervisors.
     */
    private val availableHosts: MutableSet<HostView> = mutableSetOf()

    /**
     * The servers that should be launched by the service.
     */
    private val queue: Deque<LaunchRequest> = ArrayDeque()

    /**
     * The active servers in the system.
     */
    private val activeServers: MutableSet<Server> = mutableSetOf()

    public var submittedVms: Int = 0
    public var queuedVms: Int = 0
    public var runningVms: Int = 0
    public var finishedVms: Int = 0
    public var unscheduledVms: Int = 0

    private var maxCores = 0
    private var maxMemory = 0L

    /**
     * The allocation logic to use.
     */
    private val allocationLogic = allocationPolicy()

    override val events: Flow<ComputeServiceEvent>
        get() = _events
    private val _events = EventFlow<ComputeServiceEvent>()

    /**
     * The [TimerScheduler] to use for scheduling the scheduler cycles.
     */
    private var scheduler: TimerScheduler<Unit> = TimerScheduler(scope, clock)

    override val hosts: Set<Host>
        get() = hostToView.keys

    override val hostCount: Int
        get() = hostToView.size

    override fun newClient(): ComputeClient = object : ComputeClient {
        private var isClosed: Boolean = false

        override suspend fun newServer(name: String, image: Image, flavor: Flavor): Server {
            check(!isClosed) { "Client is closed" }
            tracer.commit(VmSubmissionEvent(name, image, flavor))

            _events.emit(
                ComputeServiceEvent.MetricsAvailable(
                    this@ComputeServiceImpl,
                    hostCount,
                    availableHosts.size,
                    ++submittedVms,
                    runningVms,
                    finishedVms,
                    ++queuedVms,
                    unscheduledVms
                )
            )

            return suspendCancellableCoroutine { cont ->
                val request = LaunchRequest(createServer(name, image, flavor), cont)
                queue += request
                requestCycle()
            }
        }

        override fun close() {
            isClosed = true
        }

        override fun toString(): String = "ComputeClient"
    }

    override fun addHost(host: Host) {
        // Check if host is already known
        if (host in hostToView) {
            return
        }

        val hv = HostView(host)
        maxCores = max(maxCores, host.model.cpuCount)
        maxMemory = max(maxMemory, host.model.memorySize)
        hostToView[host] = hv

        if (host.state == HostState.UP) {
            availableHosts += hv
        }

        host.addListener(this)
    }

    override fun removeHost(host: Host) {
        host.removeListener(this)
    }

    override fun close() {
        scope.cancel()
    }

    private fun createServer(
        name: String,
        image: Image,
        flavor: Flavor
    ): Server {
        return ServerImpl(
            uid = UUID(random.nextLong(), random.nextLong()),
            name = name,
            flavor = flavor,
            image = image
        )
    }

    private fun requestCycle() {
        // Bail out in case we have already requested a new cycle.
        if (scheduler.isTimerActive(Unit)) {
            return
        }

        // We assume that the provisioner runs at a fixed slot every time quantum (e.g t=0, t=60, t=120).
        // This is important because the slices of the VMs need to be aligned.
        // We calculate here the delay until the next scheduling slot.
        val delay = schedulingQuantum - (clock.millis() % schedulingQuantum)

        scheduler.startSingleTimer(Unit, delay) {
            schedule()
        }
    }

    private fun schedule() {
        while (queue.isNotEmpty()) {
            val (server, cont) = queue.peekFirst()
            val requiredMemory = server.flavor.memorySize
            val selectedHv = allocationLogic.select(availableHosts, server)

            if (selectedHv == null || !selectedHv.host.canFit(server)) {
                logger.trace { "Server $server selected for scheduling but no capacity available for it." }

                if (requiredMemory > maxMemory || server.flavor.cpuCount > maxCores) {
                    tracer.commit(VmSubmissionInvalidEvent(server.name))

                    _events.emit(
                        ComputeServiceEvent.MetricsAvailable(
                            this@ComputeServiceImpl,
                            hostCount,
                            availableHosts.size,
                            submittedVms,
                            runningVms,
                            finishedVms,
                            --queuedVms,
                            ++unscheduledVms
                        )
                    )

                    // Remove the incoming image
                    queue.poll()

                    logger.warn("Failed to spawn $server: does not fit [${clock.millis()}]")
                    continue
                } else {
                    break
                }
            }

            logger.info { "[${clock.millis()}] Spawning $server on ${selectedHv.host.uid} ${selectedHv.host.name} ${selectedHv.host.model}" }
            queue.poll()

            // Speculatively update the hypervisor view information to prevent other images in the queue from
            // deciding on stale values.
            selectedHv.numberOfActiveServers++
            selectedHv.provisionedCores += server.flavor.cpuCount
            selectedHv.availableMemory -= requiredMemory // XXX Temporary hack

            scope.launch {
                try {
                    cont.resume(ClientServer(server))
                    selectedHv.host.spawn(server)
                    activeServers += server

                    tracer.commit(VmScheduledEvent(server.name))
                    _events.emit(
                        ComputeServiceEvent.MetricsAvailable(
                            this@ComputeServiceImpl,
                            hostCount,
                            availableHosts.size,
                            submittedVms,
                            ++runningVms,
                            finishedVms,
                            --queuedVms,
                            unscheduledVms
                        )
                    )
                } catch (e: Throwable) {
                    logger.error("Failed to deploy VM", e)

                    selectedHv.numberOfActiveServers--
                    selectedHv.provisionedCores -= server.flavor.cpuCount
                    selectedHv.availableMemory += requiredMemory
                }
            }
        }
    }

    override fun onStateChanged(host: Host, newState: HostState) {
        when (newState) {
            HostState.UP -> {
                logger.debug { "[${clock.millis()}] Host ${host.uid} state changed: $newState" }

                val hv = hostToView[host]
                if (hv != null) {
                    // Corner case for when the hypervisor already exists
                    availableHosts += hv
                }

                tracer.commit(HypervisorAvailableEvent(host.uid))

                _events.emit(
                    ComputeServiceEvent.MetricsAvailable(
                        this@ComputeServiceImpl,
                        hostCount,
                        availableHosts.size,
                        submittedVms,
                        runningVms,
                        finishedVms,
                        queuedVms,
                        unscheduledVms
                    )
                )

                // Re-schedule on the new machine
                if (queue.isNotEmpty()) {
                    requestCycle()
                }
            }
            HostState.DOWN -> {
                logger.debug { "[${clock.millis()}] Host ${host.uid} state changed: $newState" }

                val hv = hostToView[host] ?: return
                availableHosts -= hv

                tracer.commit(HypervisorUnavailableEvent(hv.uid))

                _events.emit(
                    ComputeServiceEvent.MetricsAvailable(
                        this@ComputeServiceImpl,
                        hostCount,
                        availableHosts.size,
                        submittedVms,
                        runningVms,
                        finishedVms,
                        queuedVms,
                        unscheduledVms
                    )
                )

                if (queue.isNotEmpty()) {
                    requestCycle()
                }
            }
        }
    }

    override fun onStateChanged(host: Host, server: Server, newState: ServerState) {
        val serverImpl = server as ServerImpl
        serverImpl.state = newState
        serverImpl.watchers.forEach { it.onStateChanged(server, newState) }

        if (newState == ServerState.SHUTOFF) {
            logger.info { "[${clock.millis()}] Server ${server.uid} ${server.name} ${server.flavor} finished." }

            tracer.commit(VmStoppedEvent(server.name))

            _events.emit(
                ComputeServiceEvent.MetricsAvailable(
                    this@ComputeServiceImpl,
                    hostCount,
                    availableHosts.size,
                    submittedVms,
                    --runningVms,
                    ++finishedVms,
                    queuedVms,
                    unscheduledVms
                )
            )

            activeServers -= server
            val hv = hostToView[host]
            if (hv != null) {
                hv.provisionedCores -= server.flavor.cpuCount
                hv.numberOfActiveServers--
                hv.availableMemory += server.flavor.memorySize
            } else {
                logger.error { "Unknown host $host" }
            }

            // Try to reschedule if needed
            if (queue.isNotEmpty()) {
                requestCycle()
            }
        }
    }

    public data class LaunchRequest(val server: Server, val cont: Continuation<Server>)

    private class ServerImpl(
        override val uid: UUID,
        override val name: String,
        override val flavor: Flavor,
        override val image: Image
    ) : Server {
        val watchers = mutableListOf<ServerWatcher>()

        override fun watch(watcher: ServerWatcher) {
            watchers += watcher
        }

        override fun unwatch(watcher: ServerWatcher) {
            watchers -= watcher
        }

        override suspend fun refresh() {
            // No-op: this object is the source-of-truth
        }

        override val tags: Map<String, String> = emptyMap()

        override var state: ServerState = ServerState.BUILD
    }
}
