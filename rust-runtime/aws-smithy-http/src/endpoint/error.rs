/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use std::error::Error as StdError;
use std::fmt;

/// Endpoint resolution failed
#[derive(Debug)]
pub struct ResolveEndpointError {
    message: String,
    source: Option<Box<dyn std::error::Error + Send + Sync>>,
}

impl ResolveEndpointError {
    /// Create an [`ResolveEndpointError`] with a message
    pub fn message(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            source: None,
        }
    }

    /// Add a source to the error
    pub fn with_source(self, source: Option<Box<dyn std::error::Error + Send + Sync>>) -> Self {
        Self { source, ..self }
    }
}

impl fmt::Display for ResolveEndpointError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.message)
    }
}

impl StdError for ResolveEndpointError {
    fn source(&self) -> Option<&(dyn StdError + 'static)> {
        self.source.as_ref().map(|err| err.as_ref() as _)
    }
}

#[derive(Debug)]
pub(super) enum InvalidEndpointErrorKind {
    EndpointMustHaveScheme,
    FailedToConstructAuthority {
        source: Box<dyn StdError + Send + Sync + 'static>,
    },
    FailedToConstructUri {
        source: Box<dyn StdError + Send + Sync + 'static>,
    },
}

#[derive(Debug)]
pub struct InvalidEndpointError {
    pub(super) kind: InvalidEndpointErrorKind,
}

impl InvalidEndpointError {
    pub(super) fn endpoint_must_have_scheme() -> Self {
        Self {
            kind: InvalidEndpointErrorKind::EndpointMustHaveScheme,
        }
    }

    pub(super) fn failed_to_construct_authority(
        source: impl Into<Box<dyn StdError + Send + Sync + 'static>>,
    ) -> Self {
        Self {
            kind: InvalidEndpointErrorKind::FailedToConstructAuthority {
                source: source.into(),
            },
        }
    }

    pub(super) fn failed_to_construct_uri(
        source: impl Into<Box<dyn StdError + Send + Sync + 'static>>,
    ) -> Self {
        Self {
            kind: InvalidEndpointErrorKind::FailedToConstructUri {
                source: source.into(),
            },
        }
    }
}

impl From<InvalidEndpointErrorKind> for InvalidEndpointError {
    fn from(kind: InvalidEndpointErrorKind) -> Self {
        Self { kind }
    }
}

impl fmt::Display for InvalidEndpointError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        use InvalidEndpointErrorKind as ErrorKind;
        match self.kind {
            ErrorKind::EndpointMustHaveScheme => write!(f, "endpoint must contain a valid scheme"),
            ErrorKind::FailedToConstructAuthority { .. } => write!(
                f,
                "endpoint must contain a valid authority when combined with endpoint prefix"
            ),
            ErrorKind::FailedToConstructUri { .. } => write!(f, "failed to construct URI"),
        }
    }
}

impl StdError for InvalidEndpointError {
    fn source(&self) -> Option<&(dyn StdError + 'static)> {
        use InvalidEndpointErrorKind as ErrorKind;
        match &self.kind {
            ErrorKind::FailedToConstructUri { source }
            | ErrorKind::FailedToConstructAuthority { source } => Some(source.as_ref()),
            ErrorKind::EndpointMustHaveScheme => None,
        }
    }
}
