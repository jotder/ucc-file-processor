package com.gamma.control;

import com.sun.net.httpserver.HttpExchange;

import java.util.regex.Matcher;

/** A matched-route handler: turn the request (+ path captures) into a JSON-serialisable result. */
@FunctionalInterface
interface Handler {
    Object handle(HttpExchange ex, Matcher m) throws Exception;
}
