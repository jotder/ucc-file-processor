/**
 * Provider-swappable model layer: {@code ModelTier}, {@code ModelRequest}/{@code ModelResponse},
 * {@code ModelProvider}, {@code ModelRouter}, {@code ModelProfile}. Ring-1: the {@code ModelProvider}
 * SPI is our own interface; concrete providers (Ollama, LangChain4j) live in ring-2 companions.
 *
 * <p>Implemented in K1.
 */
package com.gamma.agent.kernel.model;
