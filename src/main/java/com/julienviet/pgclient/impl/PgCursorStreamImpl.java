/*
 * Copyright (C) 2017 Julien Viet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.julienviet.pgclient.impl;

import com.julienviet.pgclient.PgStream;
import com.julienviet.pgclient.PgResult;
import com.julienviet.pgclient.Tuple;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;

import java.util.Iterator;
import java.util.UUID;

public class PgCursorStreamImpl implements PgStream<Tuple> {

  private final PgPreparedStatementImpl ps;
  private final int fetch;
  private final Tuple params;

  private Handler<Void> endHandler;
  private Handler<Tuple> rowHandler;
  private Handler<Throwable> exceptionHandler;
  private boolean paused;
  private QueryCursor cursor;

  class QueryCursor implements QueryResultHandler<Tuple> {

    final String portal = UUID.randomUUID().toString();
    Iterator<Tuple> result;
    boolean suspended;
    boolean closed;

    @Override
    public void handleResult(PgResult<Tuple> result, boolean suspended) {
      this.suspended = suspended;
      this.result = result.iterator();
    }

    @Override
    public void handleFailure(Throwable cause) {
      cursor = null;
      Handler<Throwable> handler = exceptionHandler;
      if (handler != null) {
        handler.handle(cause);
      }
      close();
    }

    @Override
    public void handleEnd() {
      checkPending();
    }

    private void checkPending() {
      while (!paused && result != null) {
        if (result.hasNext()) {
          Tuple tuple = result.next();
          Handler<Tuple> handler = rowHandler;
          if (handler != null) {
            handler.handle(tuple);
          }
        } else {
          result = null;
          if (suspended) {
            ps.execute(params, fetch, portal, true, this);
          } else {
            cursor = null;
            close();
            Handler<Void> handler = endHandler;
            if (endHandler != null) {
              handler.handle(null);
            }
          }
        }
      }
    }

    public void close() {
    }

    public void close(Handler<AsyncResult<Void>> completionHandler) {
      if (!closed) {
        closed = true;
        ps.closePortal(portal, completionHandler);
      }
    }
  }

  PgCursorStreamImpl(PgPreparedStatementImpl ps, int fetch, Tuple params) {
    this.ps = ps;
    this.fetch = fetch;
    this.params = params;
  }

  @Override
  public PgStream<Tuple> exceptionHandler(Handler<Throwable> handler) {
    exceptionHandler = handler;
    return this;
  }

  @Override
  public PgStream<Tuple> handler(Handler<Tuple> handler) {
    if (handler != null) {
      if (cursor == null) {
        rowHandler = handler;
        cursor = new QueryCursor();
        ps.execute(params, fetch, cursor.portal, false, cursor);
      } else {
        throw new UnsupportedOperationException("Handle me gracefully");
      }
    } else {
      if (cursor != null) {
        QueryCursor c = cursor;
        cursor = null;
      } else {
        rowHandler = null;
      }
    }
    return this;
  }

  @Override
  public PgStream<Tuple> pause() {
    paused = true;
    return this;
  }

  @Override
  public PgStream<Tuple> resume() {
    paused = false;
    if (cursor != null) {
      cursor.checkPending();
    }
    return this;
  }

  @Override
  public PgStream<Tuple> endHandler(Handler<Void> handler) {
    endHandler = handler;
    return this;
  }
}
