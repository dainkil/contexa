package com.contexa.server.identity;

/** 누구인지 확인할 수 없다: 토큰이 없거나, 틀렸거나, 만료·폐기됐다. 이메일이나 비밀번호가 틀린 경우도 여기다. */
public class UnauthenticatedException extends RuntimeException {

	public UnauthenticatedException(String message) {
		super(message);
	}
}
