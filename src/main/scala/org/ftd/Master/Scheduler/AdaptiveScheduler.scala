package org.ftd.Master.Scheduler

abstract class AdaptiveScheduler() extends Scheduler {
    def update(lastRunResult: SchedulingUpdateInfo): Unit
}