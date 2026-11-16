package com.jarvis.edge.core

enum class AssistantState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR,
}

fun AssistantState.canMoveTo(next: AssistantState): Boolean {
    if (next == AssistantState.IDLE || next == AssistantState.ERROR) {
        return true
    }
    return when (this) {
        AssistantState.IDLE -> next == AssistantState.LISTENING
        AssistantState.LISTENING -> next == AssistantState.THINKING
        AssistantState.THINKING -> next == AssistantState.SPEAKING
        AssistantState.SPEAKING -> next == AssistantState.LISTENING
        AssistantState.ERROR -> false
    }
}
