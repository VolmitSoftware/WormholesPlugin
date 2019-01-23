package com.volmit.wormholes.util;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

public interface IClassTweaker
{
	public byte[] transform(ClassLoader classLoader, String s, Class<?> aClass, ProtectionDomain protectionDomain, byte[] bytes) throws IllegalClassFormatException;
}
