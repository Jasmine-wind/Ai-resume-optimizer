package com.winter.airesumeoptimizer.common.exception;

import com.winter.airesumeoptimizer.common.logging.LogSanitizer;
import com.winter.airesumeoptimizer.common.result.ResultCode;
import com.winter.airesumeoptimizer.common.result.Result;
import com.winter.airesumeoptimizer.infra.ai.AiClientException;
import com.winter.airesumeoptimizer.infra.ai.AiGatewayException;
import com.winter.airesumeoptimizer.infra.ai.AiFailureCode;
import com.winter.airesumeoptimizer.infra.storage.FileStorageException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException exception, HttpServletRequest request) {
        log.warn("Business exception: code={}, path={}, message={}",
                exception.getCode(),
                request.getRequestURI(),
                LogSanitizer.sanitize(exception.getMessage()));
        return Result.failure(exception.getCode(), LogSanitizer.sanitize(exception.getMessage()), request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.failure(ResultCode.BAD_REQUEST, emptyToDefault(message, ResultCode.BAD_REQUEST.getMessage()),
                request.getRequestURI());
    }

    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException exception, HttpServletRequest request) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.failure(ResultCode.BAD_REQUEST, emptyToDefault(message, ResultCode.BAD_REQUEST.getMessage()),
                request.getRequestURI());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolationException(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        String message = exception.getConstraintViolations()
                .stream()
                .map(violation -> violation.getMessage())
                .collect(Collectors.joining("; "));
        return Result.failure(ResultCode.BAD_REQUEST, emptyToDefault(message, ResultCode.BAD_REQUEST.getMessage()),
                request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return Result.failure(ResultCode.BAD_REQUEST, "请求参数类型不正确", request.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return Result.failure(ResultCode.BAD_REQUEST, "请求体格式不正确", request.getRequestURI());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return Result.failure(ResultCode.PAYLOAD_TOO_LARGE, "简历文件大小不能超过 10 MB", request.getRequestURI());
    }

    @ExceptionHandler({
            MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class,
            MultipartException.class
    })
    public Result<Void> handleMultipartException(Exception exception, HttpServletRequest request) {
        return Result.failure(ResultCode.BAD_REQUEST, "请选择要上传的简历文件", request.getRequestURI());
    }

    @ExceptionHandler(FileStorageException.class)
    public Result<Void> handleFileStorageException(FileStorageException exception, HttpServletRequest request) {
        log.warn("File storage exception: path={}, message={}",
                request.getRequestURI(),
                LogSanitizer.sanitize(exception.getMessage()));
        return Result.failure(ResultCode.INTERNAL_ERROR, "文件存储失败，请稍后重试", request.getRequestURI());
    }

    @ExceptionHandler(AiGatewayException.class)
    public Result<Void> handleAiGatewayException(AiGatewayException exception, HttpServletRequest request) {
        AiFailureCode failureCode = exception.getFailureCode();
        int status = switch (failureCode) {
            case INVALID_CREDENTIAL, AI_CONFIGURATION_REQUIRED, CONFIGURATION_INVALID, UNSAFE_BASE_URL -> 400;
            case CREDENTIAL_CHANGED -> 409;
            default -> 502;
        };
        log.warn("AI gateway exception: code={}, path={}", failureCode, request.getRequestURI());
        return Result.failure(status, failureCode.name(), request.getRequestURI());
    }

    @ExceptionHandler(AiClientException.class)
    public Result<Void> handleAiClientException(AiClientException exception, HttpServletRequest request) {
        log.warn("AI client exception: path={}, message={}",
                request.getRequestURI(),
                LogSanitizer.sanitize(exception.getMessage()));
        return Result.failure(ResultCode.AI_SERVICE_ERROR, "AI 服务调用失败，请稍后重试", request.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public Result<Void> handleAccessDeniedException(AccessDeniedException exception, HttpServletRequest request) {
        return Result.failure(ResultCode.FORBIDDEN, ResultCode.FORBIDDEN.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception: path={}, type={}, message={}",
                request.getRequestURI(),
                exception.getClass().getName(),
                LogSanitizer.sanitize(exception.getMessage()));
        return Result.failure(ResultCode.INTERNAL_ERROR, ResultCode.INTERNAL_ERROR.getMessage(),
                request.getRequestURI());
    }

    private String emptyToDefault(String message, String defaultMessage) {
        if (message == null || message.isBlank()) {
            return defaultMessage;
        }
        return message;
    }
}
