package com.wordbattle.ui

sealed class Screen {
    object HOME : Screen()
    object HOST_SETUP : Screen()
    object HOST_WAITING : Screen()
    object HOST_GAME : Screen()
    object HOST_RESULT : Screen()
    object PLAYER_JOIN : Screen()
    object PLAYER_WAITING : Screen()
    object PLAYER_GAME : Screen()
    object PLAYER_RESULT : Screen()
    object DEBUG : Screen()
    object USER_MANAGE : Screen()
    object REVIEW : Screen()
}