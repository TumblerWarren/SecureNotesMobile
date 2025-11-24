export interface IStorageProvider {
    connect(): Promise<void>;
    read(): Promise<Uint8Array | null>;
    write(data: Uint8Array): Promise<void>;
    name: string;
}
