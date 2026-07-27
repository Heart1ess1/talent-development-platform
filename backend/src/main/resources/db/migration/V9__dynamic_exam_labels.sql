-- V9__dynamic_exam_labels.sql
ALTER TABLE employee ADD COLUMN specialty VARCHAR(50) COMMENT '专业方向，如机车、城轨';

ALTER TABLE question_bank ADD COLUMN tags JSON NULL COMMENT '专业标签，如["机车","城轨"]';

ALTER TABLE exam_paper ADD COLUMN dynamic_assembly BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE exam_paper_random_rule (
                                        id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                        paper_id BIGINT NOT NULL,
                                        question_type VARCHAR(20) NOT NULL,
                                        count INT NOT NULL,
                                        score DECIMAL(5,2) NOT NULL,
                                        tags JSON NULL COMMENT '限定标签，空或null表示公共题目',
                                        FOREIGN KEY (paper_id) REFERENCES exam_paper(id) ON DELETE CASCADE
);

CREATE TABLE exam_attempt_question (
                                       attempt_id BIGINT NOT NULL,
                                       question_id BIGINT NOT NULL,
                                       score DECIMAL(5,2) NOT NULL,
                                       sort_order INT NOT NULL,
                                       PRIMARY KEY (attempt_id, question_id),
                                       FOREIGN KEY (attempt_id) REFERENCES exam_attempt(id) ON DELETE CASCADE,
                                       FOREIGN KEY (question_id) REFERENCES question_bank(id)
);