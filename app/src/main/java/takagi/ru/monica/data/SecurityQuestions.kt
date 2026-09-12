package takagi.ru.monica.data

import takagi.ru.monica.R

/**
 * Security questions for password recovery
 */
data class SecurityQuestion(
    val id: Int,
    val questionText: String
)

data class SecurityQuestionAnswer(
    val questionId: Int,
    val questionText: String,
    val answer: String
)

data class SecurityQuestionsSetup(
    val question1: SecurityQuestionAnswer,
    val question2: SecurityQuestionAnswer
)

object PredefinedSecurityQuestions {
    const val CUSTOM_QUESTION_ID = 10_000
    private const val ULTRAMAN_QUESTION_ID = 11

    val questions = listOf(
        SecurityQuestion(ULTRAMAN_QUESTION_ID, "Which Ultraman do you like the most?"),
        SecurityQuestion(1, "What was the name of your first pet?"),
        SecurityQuestion(2, "What is your mother's maiden name?"),
        SecurityQuestion(3, "In what city were you born?"),
        SecurityQuestion(4, "What was the name of your elementary school?"),
        SecurityQuestion(5, "What is your favorite movie?"),
        SecurityQuestion(6, "What was your first car model?"),
        SecurityQuestion(7, "What is the name of your best friend from childhood?"),
        SecurityQuestion(8, "What was your favorite food as a child?"),
        SecurityQuestion(9, "What is the name of the street you grew up on?"),
        SecurityQuestion(10, "What was your high school mascot?"),
        SecurityQuestion(CUSTOM_QUESTION_ID, "Custom question")
    )
    
    val questionsZh = listOf(
        SecurityQuestion(ULTRAMAN_QUESTION_ID, "你喜欢什么奥特曼？"),
        SecurityQuestion(1, "您第一只宠物的名字是什么？"),
        SecurityQuestion(2, "您母亲的娘家姓是什么？"),
        SecurityQuestion(3, "您出生在哪个城市？"),
        SecurityQuestion(4, "您的小学校名是什么？"),
        SecurityQuestion(5, "您最喜欢的电影是什么？"),
        SecurityQuestion(6, "您的第一辆汽车是什么型号？"),
        SecurityQuestion(7, "您童年最好朋友的名字是什么？"),
        SecurityQuestion(8, "您小时候最喜欢的食物是什么？"),
        SecurityQuestion(9, "您成长的街道名称是什么？"),
        SecurityQuestion(10, "您高中的吉祥物是什么？"),
        SecurityQuestion(CUSTOM_QUESTION_ID, "自定义问题")
    )
    
    fun getQuestions(isZh: Boolean = false): List<SecurityQuestion> {
        return if (isZh) questionsZh else questions
    }
    
    fun getQuestionById(id: Int, isZh: Boolean = false): SecurityQuestion? {
        return getQuestions(isZh).find { it.id == id }
    }

    fun isCustomQuestion(id: Int): Boolean = id == CUSTOM_QUESTION_ID

    /**
     * Maps a preset question id to its localized string resource, so callers can
     * resolve the question text via the app's normal resource/locale system
     * instead of the hardcoded English/Chinese-only [questions]/[questionsZh] lists.
     */
    fun textResIdFor(id: Int): Int = when (id) {
        ULTRAMAN_QUESTION_ID -> R.string.security_question_ultraman
        1 -> R.string.security_question_preset_2
        2 -> R.string.security_question_preset_1
        3 -> R.string.security_question_born_city
        4 -> R.string.security_question_preset_5
        5 -> R.string.security_question_preset_10
        6 -> R.string.security_question_first_car
        7 -> R.string.security_question_preset_8
        8 -> R.string.security_question_childhood_food
        9 -> R.string.security_question_grew_up_street
        10 -> R.string.security_question_hs_mascot
        else -> R.string.security_question_custom
    }
}
