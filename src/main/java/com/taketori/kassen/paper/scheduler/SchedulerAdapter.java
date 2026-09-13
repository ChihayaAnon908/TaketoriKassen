package com.taketori.kassen.paper.scheduler;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;

/**
 * 调度出口（计划 §5.6）：所有延时/循环任务都从这里走。
 *
 * <p>本期只实现 Bukkit 主线程调度（Folia 暂不考虑），但把调度收在一个类里，
 * 将来若要接 Folia，只需要在这里分流到 EntityScheduler，不必翻遍技能代码。</p>
 */
public final class SchedulerAdapter {

    private final Plugin plugin;
    private final Set<BukkitTask> tasks = new HashSet<>();

    public SchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 下一 tick 执行。 */
    public void run(Runnable task) {
        track(Bukkit.getScheduler().runTask(plugin, task));
    }

    /** 延迟执行（tick）。 */
    public void runLater(Runnable task, long delayTicks) {
        track(Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(0L, delayTicks)));
    }

    /**
     * 周期性执行并返回任务句柄，供调用方自行取消。
     * "持续拉拽"这类效果需要按条件（目标已到身边 / 失效）提前结束，不能只靠 tick 计数。
     */
    public BukkitTask runTimerTask(Runnable task, long delayTicks, long periodTicks) {
        return track(Bukkit.getScheduler().runTaskTimer(plugin, task, Math.max(0L, delayTicks), Math.max(1L, periodTicks)));
    }

    /** 与实体绑定的延迟任务（未来 Folia 分流的入口）。 */
    public void runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        runLater(() -> {
            if (entity != null && entity.isValid()) {
                task.run();
            }
        }, delayTicks);
    }

    /** 当前在线主线程执行（事件回调里可直接调用）。 */
    public void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            run(task);
        }
    }

    private BukkitTask track(BukkitTask task) {
        if (task != null) {
            tasks.add(task);
        }
        return task;
    }

    public void cancelAll() {
        for (BukkitTask task : tasks) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // 插件卸载阶段忽略
            }
        }
        tasks.clear();
    }
}
