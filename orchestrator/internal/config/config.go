// Package config reads the orchestrator's settings from environment variables.
package config

import (
	"errors"
	"os"
	"strconv"
	"time"
)

type Config struct {
	Port            string        // PORT, default 8090
	DatabaseURL     string        // DATABASE_URL
	InternalToken   string        // INTERNAL_TOKEN: the shared secret the gateway and connectors send
	CheckoutBaseURL string        // CHECKOUT_BASE_URL: where customers pay; the payment id is added to the end
	FeeBasisPoints  int64         // FEE_BASIS_POINTS, default 150 (1.5%), the placeholder from decision D6
	ExpiryEvery     time.Duration // EXPIRY_INTERVAL, default 30s; 0 turns the expiry job off
}

func FromEnv() (Config, error) {
	c := Config{
		Port:            env("PORT", "8090"),
		DatabaseURL:     env("DATABASE_URL", "postgres://postgres@localhost:5432/orchestrator"),
		InternalToken:   os.Getenv("INTERNAL_TOKEN"),
		CheckoutBaseURL: env("CHECKOUT_BASE_URL", "http://localhost:8080/checkout"),
	}
	fee, err := strconv.ParseInt(env("FEE_BASIS_POINTS", "150"), 10, 64)
	if err != nil || fee < 0 || fee > 10_000 {
		return c, errors.New("FEE_BASIS_POINTS must be a whole number from 0 to 10000")
	}
	c.FeeBasisPoints = fee
	every, err := time.ParseDuration(env("EXPIRY_INTERVAL", "30s"))
	if err != nil {
		return c, errors.New("EXPIRY_INTERVAL must be a duration like 30s")
	}
	c.ExpiryEvery = every
	if len(c.InternalToken) < 32 {
		return c, errors.New("INTERNAL_TOKEN must be set to a random secret of at least 32 characters")
	}
	return c, nil
}

func env(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}
