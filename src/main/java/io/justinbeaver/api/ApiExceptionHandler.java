package io.justinbeaver.api;

import java.sql.SQLException;

import io.justinbeaver.connection.ConnectionNotFoundException;
import io.justinbeaver.driver.DriverLoadException;
import io.justinbeaver.edit.EditConflictException;
import io.justinbeaver.google.GoogleException;
import io.justinbeaver.script.ScriptNotFoundException;
import io.justinbeaver.session.NotConnectedException;
import io.justinbeaver.registry.UnknownDriverException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnknownDriverException.class)
    ProblemDetail unknownDriver(UnknownDriverException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConnectionNotFoundException.class)
    ProblemDetail connectionNotFound(ConnectionNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(GoogleException.class)
    ProblemDetail google(GoogleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(e.status()), e.getMessage());
    }

    @ExceptionHandler(ScriptNotFoundException.class)
    ProblemDetail scriptNotFound(ScriptNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(NotConnectedException.class)
    ProblemDetail notConnected(NotConnectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(EditConflictException.class)
    ProblemDetail editConflict(EditConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DriverLoadException.class)
    ProblemDetail driverLoad(DriverLoadException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    @ExceptionHandler(SQLException.class)
    ProblemDetail sql(SQLException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("sqlState", e.getSQLState());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
