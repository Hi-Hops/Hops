-- 문의 표시 번호(inquiry.inquiry_number) 발급용 시퀀스 (DATA_TYPES 신규 절 "inquiryNumber는 서버가 생성한 표시 번호, 최대 20자")
-- 번호 형식(Q-00000001)은 InquiryNumberGenerator가 정한다. 롤백된 거래가 가져간 값은 다시 쓰지 않아 번호가 건너뛸 수 있다.
-- 이미 적용된 이 파일은 고치지 않는다. 바꿀 내용이 생기면 다음 번호 파일을 새로 추가한다.
CREATE SEQUENCE inquiry_number_seq START WITH 1 INCREMENT BY 1;
