package com.keychain.wallet.api;

import com.keychain.wallet.service.IdempotencyConflictException;
import com.keychain.wallet.service.InsufficientBalanceException;
import com.keychain.wallet.service.WalletNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

/**
 * Maps domain exceptions to RFC 7807 problem-detail responses. Each problem carries a
 * stable {@code type} URI and a machine-readable {@code code} so clients (the Order
 * Service) can branch on the error programmatically instead of parsing titles.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String TYPE_BASE = "https://keychain.os/problems/";

    @ExceptionHandler(WalletNotFoundException.class)
    public ProblemDetail walletNotFound(WalletNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "wallet-not-found", "Wallet not found", e.getMessage());
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ProblemDetail insufficientBalance(InsufficientBalanceException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-balance", "Insufficient balance", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "idempotency-conflict", "Idempotency conflict", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validationFailure(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return problem(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail malformedRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed request",
                "Request body or path parameter could not be parsed");
    }

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_BASE + code));
        problem.setProperty("code", code);
        return problem;
    }
}
