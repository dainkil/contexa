package com.contexa.server.job;

/** AI 인턴에게 맡기는 일의 종류. */
public enum JobType {
	/** 문서를 발언으로 자른다. */
	PARSE,
	/** 발언에서 카드·줄 후보를 뽑는다. */
	EXTRACT,
	/** 질문에 대한 답변이나 초안을 쓴다. */
	GENERATE
}
