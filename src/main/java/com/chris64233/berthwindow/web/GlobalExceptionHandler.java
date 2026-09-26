package com.chris64233.berthwindow.web;

import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一异常处理：任何错误都返回相同结构的 {@link ErrorResponse}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return build(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "请求参数校验失败", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "请求参数校验失败", details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleMalformed(Exception ex) {
        return build(ErrorCode.VALIDATION_FAILED, "请求格式错误: " + ex.getMessage());
    }

    /**
     * 乐观锁失败：潮汐窗口或申请在判断过程中被并发修改，旧判断结果作废。
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleStale(ObjectOptimisticLockingFailureException ex) {
        return build(ErrorCode.JUDGMENT_STALE,
                "审批所依据的数据已被修改，请基于最新潮汐与申请数据重新发起审批");
    }

    /**
     * 数据库唯一约束等完整性冲突，是容量/唯一性的最终硬保护。
     * H2 对具名唯一约束保留 uk_* 名称，对 @Column(unique=true) 自动命名为 CONSTRAINT_x，
     * 因此同时按约束名与表名识别。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage()).toUpperCase();
        if (message.contains("UK_TUG_TIME") || message.contains("TUG_ASSIGNMENT")) {
            return build(ErrorCode.INSUFFICIENT_TUGS, "拖轮余量不足，并发争抢同一拖轮时刻，申请整体拒绝");
        }
        if (message.contains("UK_OCCUPATION_APPLICATION") || message.contains("BERTH_OCCUPATION")) {
            return build(ErrorCode.APPLICATION_ALREADY_APPROVED, "该申请已存在占用安排，请勿重复审批");
        }
        return build(ErrorCode.DUPLICATE_BUSINESS_KEY, "唯一约束冲突，业务编号重复");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未预期的服务异常", ex);
        return build(ErrorCode.INTERNAL_ERROR, "服务内部错误");
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, message));
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, List<String> details) {
        return ResponseEntity.status(code.getStatus()).body(ErrorResponse.of(code, message, details));
    }
}
