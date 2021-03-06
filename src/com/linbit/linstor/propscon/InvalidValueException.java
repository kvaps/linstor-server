package com.linbit.linstor.propscon;

/**
 * Thrown to indicate an invalid PropsContainer value
 *
 * @author Robert Altnoeder &lt;robert.altnoeder@linbit.com&gt;
 */
public class InvalidValueException extends Exception
{

    public InvalidValueException()
    {
    }

    public InvalidValueException(String string)
    {
        super(string);
    }
}
