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

/** Maps domain exceptions to RFC 7807 problem-detail responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(WalletNotFoundException.class)
    public ProblemDetail walletNotFound(WalletNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Wallet not found", e.getMessage());
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ProblemDetail insufficientBalance(InsufficientBalanceException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient balance", e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail idempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "Idempotency conflict", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validationFailure(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .sorted()
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail malformedRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request", "Request body or path parameter could not be parsed");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
