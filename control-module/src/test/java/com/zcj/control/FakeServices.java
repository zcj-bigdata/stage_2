package com.zcj.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Fake Ingestion and Indexing services on one JDK HTTP server, so the orchestration can be
 * tested without the real services. Each route answers 200 unless a list of scripted
 * answers is queued for it. Every call is recorded in order ("POST /ingest/1342").
 */
class FakeServices implements AutoCloseable {

    /** One scripted answer: status code, or a delay longer than the caller's timeout. */
    record Answer(int status, long delayMillis) {
        static Answer status(int status) {
            return new Answer(status, 0);
        }

        static Answer slow(long delayMillis) {
            return new Answer(200, delayMillis);
        }
    }

    private final HttpServer server;
    private final Deque<Answer> ingestAnswers = new ArrayDeque<>();
    private final Deque<Answer> indexAnswers = new ArrayDeque<>();
    final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    final Set<Integer> ingested = Collections.synchronizedSet(new HashSet<>());

    FakeServices() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    synchronized void scriptIngest(Answer... answers) {
        ingestAnswers.addAll(List.of(answers));
    }

    synchronized void scriptIndex(Answer... answers) {
        indexAnswers.addAll(List.of(answers));
    }

    /** Number of calls equal to {@code call}, e.g. "POST /ingest/1342". */
    long count(String call) {
        synchronized (calls) {
            return calls.stream().filter(call::equals).count();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        calls.add(method + " " + path);
        String[] parts = path.split("/");
        try {
            if (path.equals("/status")) {
                reply(exchange, 200, "{\"service\":\"fake\",\"status\":\"running\"}");
            } else if (method.equals("POST") && path.startsWith("/ingest/")) {
                int id = Integer.parseInt(parts[2]);
                Answer answer = next(ingestAnswers);
                if (answer.status() == 200) {
                    // The book is stored before the (possibly slow) answer is sent
                    boolean added = ingested.add(id);
                    pause(answer);
                    reply(exchange, 200, "{\"book_id\":" + id + ",\"status\":\""
                            + (added ? "downloaded" : "already_available") + "\",\"path\":\"datalake/x/" + id + "\"}");
                } else {
                    reply(exchange, answer.status(), error(answer.status()));
                }
            } else if (method.equals("GET") && path.startsWith("/ingest/status/")) {
                int id = Integer.parseInt(parts[3]);
                reply(exchange, 200, "{\"book_id\":" + id + ",\"status\":\""
                        + (ingested.contains(id) ? "available" : "not_available") + "\"}");
            } else if (method.equals("POST") && path.startsWith("/index/update/")) {
                int id = Integer.parseInt(parts[3]);
                Answer answer = next(indexAnswers);
                pause(answer);
                if (answer.status() == 200) {
                    reply(exchange, 200, "{\"book_id\":" + id + ",\"index\":\"updated\"}");
                } else {
                    reply(exchange, answer.status(), error(answer.status()));
                }
            } else {
                reply(exchange, 404, error(404));
            }
        } catch (IOException e) {
            // The caller gave up (timeout) and closed the connection
        } finally {
            exchange.close();
        }
    }

    private synchronized Answer next(Deque<Answer> answers) {
        Answer answer = answers.poll();
        return answer == null ? Answer.status(200) : answer;
    }

    private static void pause(Answer answer) {
        if (answer.delayMillis() > 0) {
            try {
                Thread.sleep(answer.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String error(int status) {
        String code = switch (status) {
            case 404 -> "not_found";
            case 409 -> "rebuild_in_progress";
            case 422 -> "invalid_book";
            case 502 -> "upstream_error";
            default -> "internal_error";
        };
        return "{\"error\":\"" + code + "\",\"message\":\"scripted " + status + "\"}";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
