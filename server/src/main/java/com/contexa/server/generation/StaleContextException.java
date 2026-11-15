package com.contexa.server.generation;

/** 글을 쓰는 사이에 참고한 카드나 줄이 바뀌었다. 저장하지 않는다. 다시 읽고 다시 써야 한다. */
public class StaleContextException extends IllegalArgumentException {

	public StaleContextException(int changed) {
		super("글을 쓰는 사이에 참고한 카드·발언·줄 " + changed + "개가 바뀌었다. 다시 읽고 다시 써야 한다");
	}
}
