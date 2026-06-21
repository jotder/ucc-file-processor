package com.gamma.control;

/**
 * A cohesive group of related control-plane routes. Implementations register their routes onto the
 * shared {@link ApiContext}; {@link ControlApi} composes them and stays a thin host — new feature
 * groups are added without editing the dispatcher (open/closed).
 */
interface RouteModule {
    void register(ApiContext api);
}
