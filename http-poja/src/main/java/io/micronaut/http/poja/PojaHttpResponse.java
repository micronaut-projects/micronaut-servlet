package io.micronaut.http.poja;

import io.micronaut.servlet.http.ServletHttpResponse;
import rawhttp.core.RawHttpResponse;

/**
 * A base class for serverless POJA responses.
 */
public abstract class PojaHttpResponse implements ServletHttpResponse<RawHttpResponse<Void>, String> {



}
