'use client';

import { createContext, useContext, useId, type FormHTMLAttributes, type ReactNode } from 'react';
import {
  Controller,
  FormProvider,
  useForm,
  useFormContext,
  type ControllerRenderProps,
  type DefaultValues,
  type FieldPath,
  type FieldValues,
  type SubmitHandler,
  type UseFormReturn,
} from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import type { z } from 'zod';
import { cn } from '../lib/cn';
import { Label } from './Label';

export { useForm, useFormContext, Controller, zodResolver };
export type { UseFormReturn, SubmitHandler };

/** Cria um formulário tipado por schema zod (react-hook-form + zodResolver). */
export function useZodForm<TSchema extends z.ZodTypeAny>(
  schema: TSchema,
  options?: {
    defaultValues?: DefaultValues<z.infer<TSchema>>;
    mode?: 'onBlur' | 'onChange' | 'onSubmit';
  },
): UseFormReturn<z.infer<TSchema>> {
  return useForm<z.infer<TSchema>>({
    resolver: zodResolver(schema as never),
    defaultValues: options?.defaultValues,
    mode: options?.mode ?? 'onSubmit',
  });
}

export interface FormProps<T extends FieldValues> extends Omit<
  FormHTMLAttributes<HTMLFormElement>,
  'onSubmit'
> {
  form: UseFormReturn<T>;
  onSubmit: SubmitHandler<T>;
  children: ReactNode;
}

/** Elemento <form> que propaga o contexto do react-hook-form. */
export function Form<T extends FieldValues>({
  form,
  onSubmit,
  children,
  className,
  ...props
}: FormProps<T>) {
  return (
    <FormProvider {...form}>
      <form
        noValidate
        onSubmit={(e) => {
          void form.handleSubmit(onSubmit)(e);
        }}
        className={cn('flex flex-col gap-4', className)}
        {...props}
      >
        {children}
      </form>
    </FormProvider>
  );
}

interface FieldContextValue {
  name: string;
  id: string;
  descriptionId: string;
  errorId: string;
  error?: string;
}

const FieldContext = createContext<FieldContextValue | null>(null);

export interface FormFieldProps<T extends FieldValues, N extends FieldPath<T>> {
  name: N;
  label: ReactNode;
  description?: ReactNode;
  required?: boolean;
  className?: string;
  /** Render prop com `field` (value/onChange/ref) e ids/aria para o controle. */
  children: (args: {
    field: ControllerRenderProps<T, N>;
    id: string;
    'aria-describedby': string | undefined;
    'aria-invalid': true | undefined;
  }) => ReactNode;
}

/** Campo de formulário: rótulo, descrição e erro associados via ARIA. */
export function FormField<T extends FieldValues, N extends FieldPath<T>>({
  name,
  label,
  description,
  required,
  className,
  children,
}: FormFieldProps<T, N>) {
  const form = useFormContext<T>();
  const base = useId();
  const id = `fld-${base}`;
  const descriptionId = `${id}-desc`;
  const errorId = `${id}-err`;

  return (
    <Controller
      control={form.control}
      name={name}
      render={({ field, fieldState }) => {
        const error = fieldState.error?.message;
        const describedBy =
          [description ? descriptionId : null, error ? errorId : null].filter(Boolean).join(' ') ||
          undefined;
        return (
          <FieldContext.Provider value={{ name, id, descriptionId, errorId, error }}>
            <div className={cn('flex flex-col gap-1', className)}>
              <Label htmlFor={id} required={required}>
                {label}
              </Label>
              {description ? (
                <p id={descriptionId} className="text-sm text-fg-muted">
                  {description}
                </p>
              ) : null}
              {children({
                field,
                id,
                'aria-describedby': describedBy,
                'aria-invalid': error ? true : undefined,
              })}
              {error ? (
                <p id={errorId} role="alert" className="text-sm font-medium text-danger-fg-subtle">
                  {error}
                </p>
              ) : null}
            </div>
          </FieldContext.Provider>
        );
      }}
    />
  );
}

export function useFormFieldContext(): FieldContextValue {
  const ctx = useContext(FieldContext);
  if (!ctx) throw new Error('useFormFieldContext deve ser usado dentro de <FormField>');
  return ctx;
}

/** Resumo de erros do formulário (anunciado a leitores de tela). */
export function FormErrorSummary({ errors, className }: { errors: string[]; className?: string }) {
  if (errors.length === 0) return null;
  return (
    <div
      role="alert"
      aria-live="assertive"
      className={cn(
        'rounded-md border border-danger bg-danger-subtle p-3 text-sm text-danger-fg-subtle',
        className,
      )}
    >
      <p className="font-semibold">Corrija os campos abaixo:</p>
      <ul className="ml-5 list-disc">
        {errors.map((e, i) => (
          <li key={i}>{e}</li>
        ))}
      </ul>
    </div>
  );
}
